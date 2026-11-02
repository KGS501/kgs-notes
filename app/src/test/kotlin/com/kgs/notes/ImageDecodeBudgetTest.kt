package com.kgs.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

class ImageDecodeBudgetTest {
    @Test
    fun aSmallPngDescribingAHugeImageIsMeasuredWithoutDecoding() {
        // 8192 x 8192 RGBA zero pixels compress to a few hundred KiB but decode to 256 MiB.
        val png = highlyCompressedPng(8192, 8192)
        assertTrue("fixture must stay far below the 64 MiB input cap", png.size < 2 * 1024 * 1024)
        assertEquals(ImageDimensions(8192, 8192), encodedImageDimensions(png))
        val budget = ImageDecodeBudget.forHeap(512L * 1024 * 1024)
        val target = budget.decodeTarget(ImageDimensions(8192, 8192))!!
        assertTrue(target.pixels <= budget.maxDecodedPixels)
        assertTrue(target.pixels * ImageDecodeBudget.PEAK_BYTES_PER_PIXEL <= 256L * 1024 * 1024)
        assertEquals(1f, target.width.toFloat() / target.height, .01f)
        assertTrue(ImageDimensions(8192, 8192).pixels / (budget.sampleSize(ImageDimensions(8192, 8192)).let { it * it }) <= budget.maxDecodedPixels)
    }

    @Test
    fun smallHeapsGetASmallerBudgetAndAbsurdHeadersAreRefused() {
        val small = ImageDecodeBudget.forHeap(64L * 1024 * 1024)
        assertTrue(small.maxDecodedPixels * ImageDecodeBudget.PEAK_BYTES_PER_PIXEL <= 32L * 1024 * 1024)
        assertNull(small.decodeTarget(ImageDimensions(65_535, 65_535)))
        assertNull(small.decodeTarget(ImageDimensions(0, 10)))
        val photo = ImageDimensions(1600, 1200)
        assertEquals(photo, ImageDecodeBudget.forHeap(512L * 1024 * 1024).decodeTarget(photo))
    }

    @Test
    fun readsJpegFrameDimensionsAfterMetadataSegments() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(),
            0xff.toByte(), 0xe1.toByte(), 0, 6, 'E'.code.toByte(), 'x'.code.toByte(), 0, 0,
            0xff.toByte(), 0xc2.toByte(), 0, 17, 8, 0x30, 0x39, 0x75, 0x30, 3) + ByteArray(12)
        assertEquals(ImageDimensions(30000, 12345), encodedImageDimensions(jpeg))
        assertNull(encodedImageDimensions(byteArrayOf(1, 2, 3)))
    }

    private fun highlyCompressedPng(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            val stream = DataOutputStream(out)
            stream.writeInt(data.size)
            val typed = type.toByteArray(Charsets.US_ASCII) + data
            stream.write(typed)
            stream.writeInt(CRC32().apply { update(typed) }.value.toInt())
        }
        val header = ByteArrayOutputStream().also { DataOutputStream(it).apply {
            writeInt(width); writeInt(height); writeByte(8); writeByte(6); writeByte(0); writeByte(0); writeByte(0)
        } }.toByteArray()
        chunk("IHDR", header)
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed, Deflater(9)).use { zipped ->
            val row = ByteArray(1 + width * 4)
            repeat(height) { zipped.write(row) }
        }
        chunk("IDAT", compressed.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }
}
