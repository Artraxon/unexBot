package de.rtrx.a.flow

import de.rtrx.a.monitor.Check
import de.rtrx.a.monitor.Monitor
import de.rtrx.a.monitor.MonitorBuilder
import net.dean.jraw.models.Comment
import net.dean.jraw.references.SubmissionReference
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import java.util.stream.Stream
import javax.inject.Provider
import kotlin.random.Random
import kotlin.reflect.full.declaredMembers
import kotlin.reflect.jvm.isAccessible


fun concatStringFromRandom(seed: Int, count: Int): String{
    val rnd = Random(seed)

    return (0..count).fold("", {acc, _ -> acc+rnd.nextBits(8).toChar().toString() })
}


open class InlineArgumentProvider(val provider: (ExtensionContext?) -> Stream<out Arguments>): ArgumentsProvider {

    override fun provideArguments(p0: ExtensionContext?) = provider(p0)

}
fun String.countChars() = this.groupBy { it }.map { it.key.toInt() to it.value.size }.sortedBy { it.first }

class EmptyCheck : Monitor {
    override suspend fun start() { }
}

class EmptyCheckBuilder: MonitorBuilder<EmptyCheck>  {
    override fun build(submission: SubmissionReference) = EmptyCheck()

    override fun setBotComment(comment: Comment?): MonitorBuilder<EmptyCheck> { return this }
}

class EmptyCheckFactory : Provider<EmptyCheckBuilder> {
    override fun get() = EmptyCheckBuilder()
}

fun <R> Collection<R>.eachAndAll(vararg predicates: (R) -> Boolean): Boolean{
    return this.all { element -> predicates.any { it(element) } } && predicates.all { this.any(it) }
}
inline fun <reified R, reified S> accessPrivateProperty(instance: R, methodName: String): S {
    return R::class.members.find { it.name == methodName }!!.apply { isAccessible = true }.call(instance) as S
}