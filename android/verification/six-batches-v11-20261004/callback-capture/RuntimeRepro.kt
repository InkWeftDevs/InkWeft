import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.structuralEqualityPolicy
fun main() {
    val refOld = callbackReference(Projection(emptyList()))
    val refNew = callbackReference(Projection(listOf("target")))
    val lambdaOld = callbackLambda(Projection(emptyList()))
    val lambdaNew = callbackLambda(Projection(listOf("target")))
    val policy = structuralEqualityPolicy<(Int)->String?>()
    println("ComposePolicy ref=${policy.equivalent(refOld,refNew)} lambda=${policy.equivalent(lambdaOld,lambdaNew)}")
    check(policy.equivalent(refOld,refNew) && !policy.equivalent(lambdaOld,lambdaNew))
    val refState = mutableStateOf(refOld)
    val lambdaState = mutableStateOf(lambdaOld)
    refState.value = refNew
    lambdaState.value = lambdaNew
    println("ActualComposeMutableState ref=${refState.value(0)} lambda=${lambdaState.value(0)}")
    check(refState.value(0) == null && lambdaState.value(0) == "target")
}
