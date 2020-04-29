package de.rtrx.a.monitor

import net.dean.jraw.models.Comment
import net.dean.jraw.references.SubmissionReference
import javax.inject.Provider

interface Monitor{
    suspend fun start()
}

interface MonitorFactory <M: Monitor, B: MonitorBuilder<M>>: Provider<B>

interface MonitorBuilder <M: Monitor>{
    fun build(submission: SubmissionReference): M
    fun setBotComment(comment: Comment?): MonitorBuilder<M>
}

abstract class Check(val submission: SubmissionReference, val botComment: Comment?) : Monitor{
    private var _checksPerformed = 0;
    val checksPerformed: Int get() = _checksPerformed
    protected fun increaseCheckCounter() = _checksPerformed++
}