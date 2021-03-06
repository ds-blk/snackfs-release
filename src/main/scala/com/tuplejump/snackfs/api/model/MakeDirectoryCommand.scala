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
 *
 */
package com.tuplejump.snackfs.api.model

import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import org.apache.hadoop.fs.Path
import org.apache.hadoop.fs.permission.FsPermission
import com.tuplejump.snackfs.api.partial.Command
import com.tuplejump.snackfs.cassandra.partial.FileSystemStore
import com.tuplejump.snackfs.fs.model.FileType
import com.tuplejump.snackfs.fs.model.INode
import com.tuplejump.snackfs.util.LogConfiguration
import com.twitter.logging.Logger
import com.tuplejump.snackfs.security.UnixGroupsMapping
import org.apache.hadoop.fs.permission.FsAction

object MakeDirectoryCommand extends Command {
  private lazy val log = Logger.get(getClass)

  private def mkdir(store: FileSystemStore, filePath: Path,  filePermission: FsPermission, atMost: FiniteDuration): Boolean = {
    val mayBeFile = Try(Await.result(store.retrieveINode(filePath), atMost))
    var result = true

    mayBeFile match {
      case Success(file: INode) =>
        if (file.isFile) {
          if(LogConfiguration.isDebugEnabled) log.debug(Thread.currentThread.getName() + " Failed to make a directory for path %s since its a file", filePath)
          result = false
        }

      case Failure(e: Throwable) =>
        val user = System.getProperty("user.name")
        val timestamp = System.currentTimeMillis()
        val iNode = INode(user, UnixGroupsMapping.getUserGroup(user), filePermission, FileType.DIRECTORY, null, timestamp)
        if(LogConfiguration.isDebugEnabled) log.debug(Thread.currentThread.getName() + " Creating directory for path %s", filePath)
        
        //we only need to check if we have WRITE permission for the file/directory and it's parent/ancestor
        store.permissionChecker.checkPermission(filePath, null, FsAction.WRITE, false, checkAncestor = true, false, atMost)
        Await.ready(store.storeINode(filePath, iNode), atMost)
    }
    result
  }

  def apply(store: FileSystemStore,
            filePath: Path,
            filePermission: FsPermission,
            atMost: FiniteDuration) = {

    var absolutePath = filePath
    var paths = List[Path]()
    var result = true

    while (absolutePath != null) {
      paths = paths :+ absolutePath
      absolutePath = absolutePath.getParent
    }

    if(LogConfiguration.isDebugEnabled) log.debug(Thread.currentThread.getName() + " Creating directories for path %s", filePath)
    result = paths.map(path => mkdir(store, path, filePermission, atMost)).reduce(_ && _)
    result
  }

}
