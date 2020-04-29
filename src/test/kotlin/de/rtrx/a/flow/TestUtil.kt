package de.rtrx.a.flow

import de.rtrx.a.monitor.Check
import de.rtrx.a.monitor.Monitor
import de.rtrx.a.monitor.MonitorBuilder
import de.rtrx.a.monitor.MonitorFactory
import net.dean.jraw.models.Comment
import net.dean.jraw.references.SubmissionReference
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import java.util.stream.Stream
import kotlin.random.Random


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

class EmptyCheckFactory : MonitorFactory<EmptyCheck, EmptyCheckBuilder> {
    override fun get() = EmptyCheckBuilder()
}
