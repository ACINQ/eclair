/*
 * Copyright 2018 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.gui.utils

import java.util

import javafx.collections.FXCollections

class IndexedObservableList[K, V] {

  val map2index = new util.HashMap[K, Int]()
  val list = FXCollections.observableArrayList[V]()

  def containsKey(key: K) = {
    map2index.containsKey(key)
  }

  def get(key: K): V = {
    if (map2index.containsKey(key)) {
      val index = map2index.get(key)
      list.get(index)
    } else throw new RuntimeException("key not found")
  }

  def put(key: K, value: V) = {
    if (map2index.containsKey(key)) {
      val index = map2index.get(key)
      list.set(index, value)
    } else {
      val index = list.size()
      map2index.put(key, index)
      list.add(index, value)
    }
  }

  def remove(key: K) = {
    if (map2index.containsKey(key)) {
      val index = map2index.get(key)
      map2index.remove(key)
      list.remove(index)
    }
  }

}
