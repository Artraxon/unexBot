package de.rtrx.a.jrawExtension

import net.dean.jraw.RedditClient
import net.dean.jraw.models.Comment
import net.dean.jraw.models.MoreChildren
import net.dean.jraw.models.PublicContribution
import net.dean.jraw.models.Submission
import net.dean.jraw.references.PublicContributionReference
import net.dean.jraw.tree.*
import java.util.ArrayDeque


class UpdatedCommentNode <R: PublicContributionReference, T: PublicContribution<R>> constructor(private val delegated: AbstractCommentNode<T>): AbstractCommentNode<T>(delegated.depth, delegated.moreChildren, delegated.subject, delegated.settings) {
    override fun walkTree(order: TreeTraversalOrder): Sequence<CommentNode<PublicContribution<*>>> {
        return UpdatedTreeTraverser.traverse(this, order)
    }

    override val parent: CommentNode<*>
        get() = delegated.parent
    override val depth: Int
        get() = delegated.depth
    override var moreChildren: MoreChildren?
        get() = delegated.moreChildren
        set(value) {delegated.moreChildren = value}
    override val replies: MutableList<CommentNode<Comment>>
        get() = delegated.replies
    override val settings: CommentTreeSettings
        get() = delegated.settings
    override val subject: T
        get() = delegated.subject

    override fun hasMoreChildren(): Boolean {
        return delegated.hasMoreChildren()
    }

    override fun loadMore(reddit: RedditClient): FakeRootCommentNode<T> {
        return delegated.loadMore(reddit)
    }

    override fun replaceMore(reddit: RedditClient): List<ReplyCommentNode> {
        return delegated.replaceMore(reddit)
    }

    override fun toString(): String {
        return delegated.toString()
    }

    override fun totalSize(): Int {
        return walkTree().count() - 1
    }

    override fun loadFully(reddit: RedditClient, depthLimit: Int, requestLimit: Int) {
        var requests = 0
        if (depthLimit < CommentNode.NO_LIMIT || requestLimit < CommentNode.NO_LIMIT)
            throw IllegalArgumentException("Expecting a number greater than or equal to -1, got " + if (requestLimit < CommentNode.NO_LIMIT) requestLimit else depthLimit)
        // Load this node's comments first
        while (hasMoreChildren()) {
            replaceMore(reddit)
            if (++requests > requestLimit && depthLimit != CommentNode.NO_LIMIT)
                return
        }

        // Load the children's comments next
        for (node in walkTree(TreeTraversalOrder.BREADTH_FIRST)) {
            // Travel breadth first so we can accurately compare depths
            if (depthLimit != CommentNode.NO_LIMIT && node.depth > depthLimit)
                return
            while (node.hasMoreChildren()) {
                node.replaceMore(reddit)
                if (++requests > requestLimit && depthLimit != CommentNode.NO_LIMIT)
                    return
            }
        }
    }
}

object UpdatedTreeTraverser {
    fun traverse(root: CommentNode<*>, order: TreeTraversalOrder): Sequence<CommentNode<*>> {
        return when (order) {
            TreeTraversalOrder.PRE_ORDER -> preOrder(root)
            TreeTraversalOrder.POST_ORDER -> postOrder(root)
            TreeTraversalOrder.BREADTH_FIRST -> breadthFirst(root)
        }
    }

    private fun preOrder(base: CommentNode<*>): Sequence<CommentNode<*>> = sequence {
        val stack = ArrayDeque<CommentNode<*>>()
        stack.add(base)

        var root: CommentNode<*>
        while (!stack.isEmpty()) {
            root = stack.pop()
            yield(root)

            if (root.replies.isNotEmpty()) {
                for (i in root.replies.size - 1 downTo 0) {
                    stack.push(root.replies[i])
                }
            }
        }
    }

    private fun postOrder(base: CommentNode<*>): Sequence<CommentNode<*>> = sequence {
        // Post-order traversal isn't going to be as fast as the other methods, this traversal method discovers elements
        // in reverse order and sorts them using a stack. Instead of finding the next node and yielding it, we find the
        // entire sequence and yield all elements right then and there
        val unvisited = ArrayDeque<CommentNode<*>>()
        val visited = ArrayDeque<CommentNode<*>>()
        unvisited.add(base)
        var root: CommentNode<*>

        while (unvisited.isNotEmpty()) {
            root = unvisited.pop()
            visited.push(root)

            if (root.replies.isNotEmpty()) {
                for (reply in root.replies) {
                    unvisited.push(reply)
                }
            }
        }

        yieldAll(visited)
    }

    private fun breadthFirst(base: CommentNode<*>): Sequence<CommentNode<*>> = sequence {
        val queue = ArrayDeque<CommentNode<*>>()
        var node: CommentNode<*>

        queue.add(base)

        while (queue.isNotEmpty()) {
            node = queue.remove()
            yield(node)

            queue += node.replies
        }
    }
}