package de.rtrx.a.flow

import de.rtrx.a.flow.events.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsSource
import kotlin.random.Random


class FlowTest {

    @ParameterizedTest
    @ArgumentsSource(StringConcatFlowProvider::class)
    fun testStringConcatFlow(seed: Int, tries: Int, failAt: Int, fails: Boolean){
        println("seed: $seed, tries: $tries, failAt: $failAt, fails: $fails")
        val rnd = Random(seed)
        val source = TestableStringEvent(seed, 1) { _ -> Unit}
        var result: String? = null
        var flResult: FlowResult<StringFlow>? = null
        val flow = with(StringFlowBuilder(source)) {
            setSubscribeAccess { stringFlow, function: suspend (String) -> Unit, _ ->
                runBlocking {
                    for (i in 0..tries) {
                        function(rnd.nextBits(8).toChar().toString())
                    } } }
            setSuccessAt(tries)
            setFailAt(failAt)
            setFinishCallback(Callback {flRes  -> result = flRes.finishedFlow.string; flResult = flRes })
        }.build()

        runBlocking {
            flow.start()
        }

        if(fails){
            assert(flResult is FlowResult.FailedEnd)
        } else {
            assert(flResult is FlowResult.NotFailedEnd<StringFlow>)
        }

        assert(result?.equals(concatStringFromRandom(seed, tries.coerceAtMost(failAt) - 1)) ?: false)
    }

    class StringConcatFlowProvider : InlineArgumentProvider( {
        listOf(0, 20, 1000).map { seed ->
            listOf(
                    Arguments.of(20, 10, 15, false),
                    Arguments.of(20, 15, 10, true),
                    Arguments.of(20, 10, 10, false))
        }.flatten().stream()
    })


    @ParameterizedTest
    @ArgumentsSource(StringEventProvider::class)
    fun `test Event Output`(seed: Int, tries: Int, failAt: Int, fails: Boolean, delay: Long){
        val (source, out) = TestableStringEvent.create(seed to delay)

        val multiplexer = SimpleMultiplexer.SimpleMultiplexerBuilder<String>().setOrigin(out!!).build()

        val result: CompletableDeferred<FlowResult<StringFlow>> = CompletableDeferred()
        val flow = with(StringFlowBuilder(source)){
            setSuccessAt(tries)
            setFailAt(failAt)
            setSubscribeAccess {stringFlow, function: suspend (String) -> Unit, eventType ->
                if(eventType === source){
                    multiplexer.addListener(stringFlow, function)
                    source.start()
                } else throw Throwable("Wrong event type")
            }
            setFinishCallback(Callback {pair -> result.complete(pair); out?.cancel()})
        }.build()

        runBlocking {
            flow.start()
            result.await()
        }

        if(fails){
            assert(result.getCompleted() is FlowResult.FailedEnd<StringFlow>)
        } else {
            assert(result.getCompleted() is FlowResult.NotFailedEnd<StringFlow>)
        }

        val correctString = concatStringFromRandom(seed, tries.coerceAtMost(failAt) - 1).countChars()
        assert(result.getCompleted().finishedFlow.string.countChars() == correctString)
                { "expected $correctString but got ${result.getCompleted().finishedFlow.string.countChars()}" }
    }

    class StringEventProvider : InlineArgumentProvider( {
        listOf(0, 20, 1000).map { seed ->
            listOf(0, 1, 10).map { delay ->
                listOf(
                        Arguments.of(seed, 5, 15, false, delay),
                        Arguments.of(seed, 15, 10, true, delay),
                        Arguments.of(seed, 10, 10, false, delay))
            }.flatten()
        }.flatten().stream()
    })
}



class StringFlowBuilder(val eventSource: TestableStringEvent) : FlowBuilderDSL<StringFlow, String>(){
    private var failAt= 15
    private var successAt = 10

    init {
        _initValue = ""
    }

    fun setFailAt(failAt: Int): StringFlowBuilder {
        this.failAt = failAt
        return this
    }

    fun setSuccessAt(successAt: Int): StringFlowBuilder {
        this.successAt = successAt
        return this
    }
    override fun build(): StringFlow {
        val stub = FlowStub("", _subscribeAccess, _unsubscribeAccess, CoroutineScope(Dispatchers.Default))
        return StringFlow(_callback, eventSource, failAt, successAt, stub)
    }
}

class StringFlow(
        val callback: Callback<in FlowResult<StringFlow>, Unit>,
        val eventSource: TestableStringEvent,
        val failAt: Int,
        val successAt: Int,
        private val stub: FlowStub<String, StringFlow>
) : IFlowStub<String> by stub, Flow {
    var string = initValue
    override suspend fun start() {
        stub.setOuter(this)
        subscribe(this::concat, eventSource)
    }

    suspend fun concat(str: String){
        if(string.length < failAt && string.length < successAt) string += str;
        else if (string.length == successAt) { callback(FlowResult.NotFailedEnd.RegularEnd(this) ); return }
        else if (string.length == failAt) callback(FlowResult.FailedEnd.LogicFailed(this))
    }
}

class TestableStringEvent(seed: Int, delay: Long = 0, outReceiver: (() -> ReceiveChannel<String>) -> Unit): EventStream<String>(outReceiver){

    private val start: CompletableDeferred<Unit> = CompletableDeferred()
    fun start() = start.complete(Unit)

    override val out: ReceiveChannel<String> = CoroutineScope(Dispatchers.Default).produce(capacity = Channel.RENDEZVOUS) {
        val rnd = Random(seed)
        start.await()
        while(isActive){
            val nextChar = rnd.nextBits(8).toChar().toString()
            send(nextChar)
            delay(delay)
        }
    }

    companion object : EventTypeFactory<TestableStringEvent, String, Pair<Int, Long>> by  UniversalEventTypeFactory( {(seed, delay) ->
        var out: () -> ReceiveChannel<String> = {throw UninitializedPropertyAccessException()}
        val built = TestableStringEvent(seed, delay) { channel -> out = channel}
        return@UniversalEventTypeFactory built to out()
    } )

}