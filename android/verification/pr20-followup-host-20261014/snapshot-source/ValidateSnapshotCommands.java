import java.nio.file.*;
import java.util.*;
import org.inkweft.core.SaveNote;
class ValidateSnapshotCommands {
 static String id(Random r){return new UUID(r.nextLong(),r.nextLong()).toString();}
 public static void main(String[] args)throws Exception {
  String source=Files.readString(Path.of(args[0]));
  for(String needle:List.of("java.util.Random(4101L)","repeat(500)","repeat(40)","operationId=id()","SaveNote(id(),writer.id,writer.revision,writer.title,\"Synthetic controlled edit $sample\")"))
   if(!source.contains(needle))throw new AssertionError("Changed source recipe: "+needle);
  List<String> expected=null;
  for(int arm=0;arm<3;arm++){
   Random r=new Random(4101L);for(int i=0;i<20001;i++)id(r);String author=id(r);
   List<String> commands=new ArrayList<>();
   for(int sample=0;sample<30;sample++){
    String command=id(r);new SaveNote(command,author,1L+sample,"Concurrent author","Synthetic controlled edit "+sample);
    if(author.equals(command)||commands.contains(command))throw new AssertionError("Identity collision");commands.add(command);
   }
   if(expected!=null&&!expected.equals(commands))throw new AssertionError("Arms have different commands");expected=commands;
  }
  System.out.println("PASS: 90 actual core SaveNote constructions; identical seeded author/30 commands per arm, valid UUIDs and revisions.");
  System.out.println("Host constructor/recipe check only; no SQLite/Room/Android or measured fixture digest produced.");
 }
}
