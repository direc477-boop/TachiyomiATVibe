@file:JvmName("OkioZstd")

package com.squareup.zstd.okio

import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import okio.Sink
import okio.Source
import okio.buffer
import okio.sink
import okio.source

fun Source.zstdDecompress(): Source = ZstdInputStream(buffer().inputStream()).source()

fun Sink.zstdCompress(): Sink = ZstdOutputStream(buffer().outputStream()).sink()
