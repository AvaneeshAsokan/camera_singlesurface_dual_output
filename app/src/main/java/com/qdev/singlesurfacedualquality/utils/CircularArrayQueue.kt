package com.qdev.singlesurfacedualquality.utils

import android.renderscript.Int2
import android.util.Log
import com.qdev.singlesurfacedualquality.YUV420
import java.nio.ByteBuffer

/*class CircularArrayQueue<T>(private val capacity: Int) {
    private val array: Array<Any?> = arrayOfNulls(capacity)
    private var front = 0
    private var rear = -1
    private var size = 0

    fun enqueue(item: T) {
        if (size == capacity) {
            throw IllegalStateException("Queue is full")
        }
        rear = (rear + 1) % capacity
        array[rear] = item
        size++
    }

    fun dequeue(): T? {
        if (size == 0) {
            throw IllegalStateException("Queue is empty")
        }
        val item = array[front] as T
        array[front] = null
        front = (front + 1) % capacity
        size--
        return item
    }

    fun peek(): T? {
        if (size == 0) {
            return null
        }
        return array[front] as T
    }

    fun isFull(): Boolean {
        return size == capacity
    }

    fun isEmpty(): Boolean {
        return size == 0
    }

    fun size(): Int {
        return size
    }
}*/

class CircularArrayQueue(val capacity: Int, yBuffSize: Int, uBuffSize: Int, vBuffSize: Int) {
    private val TAG = CircularArrayQueue::class.java.canonicalName
    private val array: Array<YUV420?> = arrayOfNulls(capacity)
    private var front = 0
    private var rear = -1
    private var size = 0

    init {
        // Pre-allocate YUV420 objects to reuse
        for (i in 0 until capacity) {
            Log.d(TAG, "CircularArrayQueue: initialized ${i + 1} of $capacity")
            array[i] = YUV420(
                width = 0, height = 0,
                y = ByteBuffer.allocateDirect(yBuffSize),
                u = ByteBuffer.allocateDirect(uBuffSize),
                v = ByteBuffer.allocateDirect(vBuffSize),
                yRowStride = 0, uRowStride = 0, vRowStride = 0,
                yPixelStride = 0, uPixelStride = 0, vPixelStride = 0,
                timestampUs = 0
            )
        }
    }

    fun enqueue(
        width: Int,
        height: Int,
        y: ByteBuffer,
        u: ByteBuffer,
        v: ByteBuffer,
        yRowStride: Int,
        uRowStride: Int,
        vRowStride: Int,
        yPixelStride: Int,
        uPixelStride: Int,
        vPixelStride: Int,
        timestampUs: Long
    ) {
        if (size == capacity) {
            // If the queue is full, overwrite the oldest entry
            front = (front + 1) % capacity
            size--
        }
        rear = (rear + 1) % capacity

        // Reuse the YUV420 object by updating its fields
        val yuv420 = array[rear]!!
        yuv420.width = width
        yuv420.height = height
        yuv420.timestampUs = timestampUs
        yuv420.yBuffer. apply {
            clear()
            put(y)
            flip()
        }
        yuv420.uBuffer. apply {
            clear()
            put(u)
            flip()
        }
        yuv420.vBuffer. apply {
            clear()
            put(v)
            flip()
        }
        yuv420.yRowStride = yRowStride
        yuv420.uRowStride = uRowStride
        yuv420.vRowStride = vRowStride
        yuv420.yPixelStride = yPixelStride
        yuv420.uPixelStride = uPixelStride
        yuv420.vPixelStride = vPixelStride

        size++
    }

    fun dequeue(): YUV420? {
        if (size == 0) {
            throw IllegalStateException("Queue is empty")
        }
        val item = array[front]
        front = (front + 1) % capacity
        size--
        return item
    }

    fun peek(): YUV420? {
        if (size == 0) {
            return null
        }
        return array[front]
    }

    fun isFull(): Boolean {
        return size == capacity
    }

    fun isEmpty(): Boolean {
        return size == 0
    }

    fun size(): Int {
        return size
    }
}
