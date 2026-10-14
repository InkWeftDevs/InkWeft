// Offline validation against the existing compiled core SaveNote constructor.
// This is not a Room/native test and does not claim the unavailable JUnit stack.
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.regex.Pattern;
import org.inkweft.core.SaveNote;

class ValidateGuardIds {
    static int constructed;
    static final HashSet<String> commands = new HashSet<>();
    static final HashSet<String> authors = new HashSet<>();
    static void check(String command, String author, long revision) {
        new SaveNote(command, author, revision, "Title", "Body");
        if (!commands.add(command)) throw new AssertionError("Repeated fixture command identity");
        authors.add(author);
        constructed++;
    }
    public static void main(String[] args) throws Exception {
        String source = Files.readString(Path.of(args[0]));
        String[] starts = {"@Test fun boundariesPreserveCommitRollbackReplayAndRecovery", "@Test fun observerFailureOverflowAndDelegateFailureDoNotChangeSaveOutcome"};
        for (int i=0; i<starts.length; i++) {
            int start=source.indexOf(starts[i]);
            int end=source.indexOf(i==0 ? starts[1] : "private fun assertOuterPair", start);
            if (start<0 || end<0) throw new AssertionError("Missing guard boundary");
            String guard=source.substring(start,end);
            var authorMatch=Pattern.compile("val authorId=\"([^\"]+)\"").matcher(guard);
            if (!authorMatch.find()) throw new AssertionError("Missing fixed author ID");
            String author=authorMatch.group(1);
            if (authorMatch.find()) throw new AssertionError("Ambiguous author ID");
            var constructors=Pattern.compile("SaveNote\\(\"([^\"]+)\",(?:authorId|\"([^\"]+)\"),([0-9]+),").matcher(guard);
            while(constructors.find()) check(constructors.group(1),constructors.group(2)==null?author:constructors.group(2),Long.parseLong(constructors.group(3)));
            var copies=Pattern.compile("command\\.copy\\(commandId=\"([^\"]+)\",expectedRevision=([0-9]+),").matcher(guard);
            while(copies.find()) check(copies.group(1),author,Long.parseLong(copies.group(2)));
            // The conflict constructor has no following text argument.
            copies=Pattern.compile("command\\.copy\\(commandId=\"([^\"]+)\",expectedRevision=([0-9]+)\\)").matcher(guard);
            while(copies.find()) check(copies.group(1),author,Long.parseLong(copies.group(2)));
        }
        if(constructed!=10 || authors.size()!=3) throw new AssertionError("Incomplete guard coverage: "+constructed+"/"+authors.size());
        for(String command:commands) if(authors.contains(command)) throw new AssertionError("Command and notebook identities overlap");
        int rejected=0;
        try { new SaveNote("first","author",0,"Title","Body"); } catch(IllegalArgumentException expected) { rejected++; }
        try { new SaveNote("00000000-0000-0000-0000-000000000002","author",0,"Title","Body"); } catch(IllegalArgumentException expected) { rejected++; }
        if(rejected!=2) throw new AssertionError("Original invalid-ID contract not reproduced");
        System.out.println("PASS: 10 source-derived distinct command constructors, 3 notebook IDs; original invalid command/note IDs each reject in actual core SaveNote constructor.");
        System.out.println("Scope: host JVM constructor contract only; no Room/native execution or recovered JUnit stack.");
    }
}
