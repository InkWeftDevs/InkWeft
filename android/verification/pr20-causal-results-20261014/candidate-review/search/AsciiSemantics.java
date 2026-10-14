import java.util.*;
import kotlin.text.StringsKt;

/** Scratch host semantics only; no timings and no Android performance inference. */
public class AsciiSemantics {
  static long cases, prefixMismatches, lowerMismatches, prefixVerifications;
  static boolean original(String text, String query) { return StringsKt.contains(text, query, true); }
  static boolean ascii(String s) { for(int i=0;i<s.length();i++)if(s.charAt(i)>127)return false;return true; }
  static char folded(char c) { return c>='A'&&c<='Z'?(char)(c+32):c; }
  static boolean lower(String text, String query) {
    return ascii(query)&&ascii(text) ? text.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)) : original(text,query);
  }
  static boolean prefix(String text, String query) {
    if(query.isEmpty())return true;
    if(query.charAt(0)>127)return original(text,query);
    int end=text.length()-query.length();char first=folded(query.charAt(0));
    for(int i=0;i<=end;i++){
      char c=text.charAt(i);
      if(c<=127&&folded(c)!=first)continue;
      prefixVerifications++;
      // Same receiver/argument direction as cached Kotlin 2.3.10 StringsKt bytecode.
      if(query.regionMatches(true,0,text,i,query.length()))return true;
    }
    return false;
  }
  static void check(String text,String query) {
    boolean expected=original(text,query);cases++;
    if(prefix(text,query)!=expected)prefixMismatches++;
    if(lower(text,query)!=expected)lowerMismatches++;
  }
  static String quoted(String s) {
    StringBuilder b=new StringBuilder("\"");
    for(int i=0;i<s.length();i++){
      char c=s.charAt(i);
      if(c<' '||c>'~'||c=='"'||c=='\\')b.append(String.format("\\u%04x",(int)c));else b.append(c);
    }
    return b.append('"').toString();
  }
  public static void main(String[] args) {
    for(int t=0;t<128;t++)for(int q=0;q<128;q++)check(""+(char)t,""+(char)q);
    String[] queries={"a","i","k","s","Z","_","\u0000","iA",""};
    for(int t=0;t<=65535;t++)for(String q:queries)check(""+(char)t,q);
    Random random=new Random(391731L);
    char[] alphabet="aAzZIİıSſKKΣσςÅé中🚀𐐀\u0000\t%_[]{}!?".toCharArray();
    for(int n=0;n<10000;n++){
      StringBuilder text=new StringBuilder(),query=new StringBuilder();
      for(int i=random.nextInt(65);i>0;i--)text.append(alphabet[random.nextInt(alphabet.length)]);
      for(int i=random.nextInt(9);i>0;i--)query.append(alphabet[random.nextInt(alphabet.length)]);
      check(text.toString(),query.toString());
      if(text.length()>0){int at=random.nextInt(text.length());check(text.toString(),text.substring(at));}
    }
    for(String text:List.of("", "A\u0000B", "İıſK", "😀abc", "x𐐀z", "合成前缀"+"b".repeat(20000), "A".repeat(100000)))
      for(String q:List.of("", "\u0000", "b", "target-only", "a", "s", "i", "k", "𐐨", "a\u0000"))check(text,q);
    long before=prefixVerifications;
    boolean example=prefix("合成前缀"+"b".repeat(20000),"target-only");
    System.out.println("{\"scope\":\"HOST_SEMANTICS_ONLY\",\"javaVersion\":"+quoted(System.getProperty("java.version"))+",\"kotlinVersion\":\"2.3.10\",\"cases\":"+cases+",\"prefixMismatches\":"+prefixMismatches+",\"lowerMismatches\":"+lowerMismatches+",\"mixedPrefixExampleResult\":"+example+",\"mixedPrefixExampleRegionVerifications\":"+(prefixVerifications-before)+"}");
    if(prefixMismatches!=0||lowerMismatches!=0)throw new AssertionError("semantic mismatch");
  }
}
