data class Projection(val rows: List<String>)
fun callbackReference(projection: Projection): (Int) -> String? {
    fun updateOutlinePointer(index: Int): String? = projection.rows.getOrNull(index)
    return ::updateOutlinePointer
}
fun callbackLambda(projection: Projection): (Int) -> String? {
    fun updateOutlinePointer(index: Int): String? = projection.rows.getOrNull(index)
    return { updateOutlinePointer(it) }
}
fun main() {
    val empty = Projection(emptyList())
    val loaded = Projection(listOf("target"))
    val referenceBefore = callbackReference(empty)
    val referenceAfter = callbackReference(loaded)
    val lambdaBefore = callbackLambda(empty)
    val lambdaAfter = callbackLambda(loaded)
    println("Kotlin=" + KotlinVersion.CURRENT)
    println("refSameIdentity=${referenceBefore === referenceAfter} refEquals=${referenceBefore == referenceAfter} before=${referenceBefore(0)} after=${referenceAfter(0)}")
    println("lambdaSameIdentity=${lambdaBefore === lambdaAfter} lambdaEquals=${lambdaBefore == lambdaAfter} before=${lambdaBefore(0)} after=${lambdaAfter(0)}")
    var storedReference = referenceBefore
    if(storedReference != referenceAfter) storedReference = referenceAfter
    var storedLambda = lambdaBefore
    if(storedLambda != lambdaAfter) storedLambda = lambdaAfter
    println("structuralStateReference=${storedReference(0)} structuralStateLambda=${storedLambda(0)}")
    check(referenceBefore !== referenceAfter && referenceBefore == referenceAfter)
    check(storedReference(0) == null && storedLambda(0) == "target")
}
