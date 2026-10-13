import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import kotlin.text.HexExtensionsKt;
import kotlin.text.HexFormat;

/** One-off host representation check; deliberately not a performance benchmark. */
public final class RasterHexCompatibility {
    public static void main(String[] args) throws Exception {
        List<byte[]> cases = new ArrayList<>();
        cases.add(new byte[0]);
        byte[] all = new byte[256];
        byte[] reversed = new byte[256];
        for (int i = 0; i < 256; i++) {
            all[i] = (byte) i;
            reversed[i] = (byte) (255 - i);
            cases.add(new byte[] {(byte) i});
        }
        cases.add(all);
        cases.add(reversed);
        cases.add(new byte[32]);
        byte[] ff = new byte[32];
        Arrays.fill(ff, (byte) 0xff);
        cases.add(ff);
        String[] inputs = {"", "InkWeft", "笔迹", "region:0:256:512", "layer-alpha", "x".repeat(1000)};
        for (String input : inputs) {
            cases.add(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        }
        Locale[] locales = {Locale.ROOT, Locale.US, Locale.CHINA, Locale.forLanguageTag("tr-TR"),
            Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("fa-IR"), Locale.forLanguageTag("hi-IN")};
        Locale previous = Locale.getDefault();
        Locale previousFormat = Locale.getDefault(Locale.Category.FORMAT);
        Locale previousDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        int checks = 0;
        try {
            for (Locale locale : locales) {
                Locale.setDefault(locale);
                Locale.setDefault(Locale.Category.FORMAT, locale);
                for (byte[] value : cases) {
                    StringBuilder old = new StringBuilder();
                    for (byte b : value) old.append(String.format("%02x", Byte.valueOf(b)));
                    String actual = HexExtensionsKt.toHexString(value, HexFormat.Companion.getDefault());
                    if (!old.toString().equals(actual) || actual.length() != 2 * value.length
                            || !actual.matches("[0-9a-f]*")) {
                        throw new AssertionError("Hex mismatch in " + locale.toLanguageTag() + " case " + checks);
                    }
                    checks++;
                }
            }
        } finally {
            Locale.setDefault(previous);
            Locale.setDefault(Locale.Category.FORMAT, previousFormat);
            Locale.setDefault(Locale.Category.DISPLAY, previousDisplay);
        }
        System.out.println("{\"status\":\"PASS\",\"checks\":" + checks
            + ",\"casesPerLocale\":" + cases.size() + ",\"locales\":[\"und\",\"en-US\",\"zh-CN\",\"tr-TR\",\"ar-EG\",\"fa-IR\",\"hi-IN\"]"
            + ",\"all256ByteValuesIndividually\":true,\"ascendingAndDescending256ByteArrays\":true"
            + ",\"emptyArray\":true,\"zeroAndFF32ByteArrays\":true,\"sha256Arrays\":" + inputs.length
            + ",\"defaultLowercaseTwoCharactersPerByte\":true,\"scope\":\"HOST_REPRESENTATION_COMPATIBILITY_ONLY_NOT_NATIVE_PERFORMANCE\"}");
    }
}
