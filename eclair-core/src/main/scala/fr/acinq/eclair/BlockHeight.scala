/*
 * Copyright 2022 ACINQ SAS
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

package fr.acinq.eclair

/**
 * Created by t-bast on 11/01/2022.
 */

case class BlockHeight(private val underlying: Long) extends Ordered[BlockHeight] {
  // @formatter:off
  override def compare(other: BlockHeight): Int = underlying.compareTo(other.underlying)
  def +(other: BlockHeight) = BlockHeight(underlying + other.underlying)
  def +(i: Int) = BlockHeight(underlying + i)
  def +(l: Long) = BlockHeight(underlying + l)
  def -(other: BlockHeight) = BlockHeight(underlying - other.underlying)
  def -(i: Int) = BlockHeight(underlying - i)
  def -(l: Long) = BlockHeight(underlying - l)
  def unary_- = BlockHeight(-underlying)

  def toLong: Long = underlying
  def toInt: Int = underlying.toInt
  override def toString = underlying.toString
  // @formatter:on
}
