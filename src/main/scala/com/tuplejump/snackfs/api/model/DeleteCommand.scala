/*
 * Licensed to Tuplejump Software Pvt. Ltd. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Tuplejump Software Pvt. Ltd. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tuplejump.snackfs.api.model

import java.io.IOException

import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import org.apache.hadoop.fs.Path

import com.tuplejump.snackfs.SnackFS
import com.tuplejump.snackfs.api.partial.Command
import com.tuplejump.snackfs.cassandra.partial.FileSystemStore
import com.tuplejump.snackfs.fs.model.INode
import com.tuplejump.snackfs.util.LogConfiguration
import com.twitter.logging.Logger


object DeleteCommand extends Command {
  private lazy val log = Logger.get(getClass)

  def apply(fs: SnackFS,
            store: FileSystemStore,
            srcPath: Path,
            isRecursive: Boolean,
            atMost: FiniteDuration): Boolean = {

    val absolutePath = srcPath
    val mayBeSrc = Try(Await.result(store.retrieveINode(absolutePath), atMost))
    var result = true

    mayBeSrc match {

      case Success(src: INode) =>
        if (src.isFile) {
          if(LogConfiguration.isDebugEnabled) log.debug(Thread.currentThread.getName() + " deleting file %s", srcPath)
          Await.ready(store.deleteINode(absolutePath), atMost)
          Await.ready(store.deleteBlocks(src), atMost)

        } else {
          val contents = ListCommand(fs, store, srcPath, atMost)

          if (contents.length == 0) {
            if(LogConfiguration.isDebugEnabled) log.debug(Thread.currentThread.getName() + " deleting directory %s", srcPath)
            Await.ready(store.deleteINode(absolutePath), atMost)

          } else if (!isRecursive) {
            val ex = new IOException("Directory is not empty")
            log.error(ex, Thread.currentThread.getName() + "Failed to delete directory %s as it is not empty", srcPath)
            throw ex

          } else {
            if(LogConfiguration.isDebugEnabled) log.debug(Thread.currentThread.getName() + " deleting directory %s and all its contents", srcPath)
            result = contents.map(p => DeleteCommand(fs, store, p.getPath, isRecursive, atMost)).reduce(_ && _)
            Await.ready(store.deleteINode(absolutePath), atMost)
          }
        }

      case Failure(e: Throwable) =>
        if(LogConfiguration.isDebugEnabled) log.debug(Thread.currentThread.getName() + " failed to delete %s, as it doesn't exist", srcPath)
        result = false
    }
    result
  }

}

