/*
 * Copyright 2017 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.scio.extra

import java.io.File

import com.google.common.io.Files
import com.spotify.scio.ScioContext
import com.spotify.scio.testing.PipelineSpec
import com.spotify.sparkey.{Sparkey => JSparkey}

class SparkeyTest extends PipelineSpec {
  import Sparkey._

  val sideData = Seq(("a", "1"), ("b", "2"), ("c", "3"))

  "SCollection" should "support .asSparkey with default local file" in {
    val tmpDir = Files.createTempDir().toString
    val sparkeyRoot = tmpDir + "/sparkey"
    val sc = ScioContext()
    sc.options.setTempLocation(tmpDir)
    val p = sc.parallelize(sideData).asSparkey
    sc.close().waitUntilFinish()
    val reader = JSparkey.open(new File(sparkeyRoot))
    reader.toStream.toSet shouldEqual Set(("a", "1"), ("b", "2"), ("c", "3"))
    for (ext <- Seq(".spi", ".spl")) {
      new File(sparkeyRoot + ext).delete()
    }
  }

  it should "support .asSparkey with specified local file" in {
    val tmpDir = Files.createTempDir()
    val sparkeyRoot = tmpDir + "/my-sparkey-file"
    runWithContext { sc =>
      val p = sc.parallelize(sideData).asSparkey(SparkeyUri(sparkeyRoot))
    }
    val reader = JSparkey.open(new File(sparkeyRoot + ".spi"))
    reader.toStream.toSet shouldEqual Set(("a", "1"), ("b", "2"), ("c", "3"))
    for (ext <- Seq(".spi", ".spl")) {
      new File(sparkeyRoot + ext).delete()
    }
  }
}