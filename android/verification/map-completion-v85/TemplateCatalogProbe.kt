// Original InkWeft template projection; no reference application code.
import org.inkweft.core.*
private fun quote(s:String)="\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\""
fun main(){
    println(MapTemplates.builtins.joinToString(prefix="[",postfix="]"){t->
        "{\"title\":"+quote(t.title)+",\"layout\":"+quote(t.layout)+",\"layoutLabel\":"+quote(MapLayouts.label(t.layout))+",\"category\":"+quote(MapTemplates.category(t))+",\"description\":"+quote(MapTemplates.description(t))+",\"nodes\":"+
            t.nodes.joinToString(prefix="[",postfix="]"){n->"{\"title\":"+quote(n.title)+",\"parent\":"+(n.parent?.toString()?:"null")+",\"x\":"+n.x+",\"y\":"+n.y+"}"}+"}"
    })
}
