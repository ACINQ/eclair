/*
 * Copyright 2019 ACINQ SAS
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

import java.time.Instant
import java.sql

//case class TimestampSecond(private val underlying: Long) extends Ordered[TimestampSecond] {
//  // @formatter: off
//  def toLong: Long = underlying
//  def toSqlTimestamp: sql.Timestamp = sql.Timestamp.from(Instant.ofEpochSecond(underlying))
//  override def toString: String = underlying.toString
//  override def compare(that: TimestampSecond): Int = underlying.compareTo(that.underlying)
//  // @formatter: on
//}
//
//object TimestampSecond {
//  def now: TimestampSecond = TimestampSecond(System.currentTimeMillis() / 1000)
//}

case class TimestampMilli(private val underlying: Long) extends Ordered[TimestampMilli] {
  // @formatter: off
  def toLong: Long = underlying
  def toSqlTimestamp: sql.Timestamp = sql.Timestamp.from(Instant.ofEpochMilli(underlying))
  override def toString: String = underlying.toString
  override def compare(that: TimestampMilli): Int = underlying.compareTo(that.underlying)
  // @formatter: on
}

object TimestampMilli {
  def now: TimestampMilli = TimestampMilli(System.currentTimeMillis())

  def fromSqlTimestamp(sqlTs: sql.Timestamp): TimestampMilli = TimestampMilli(sqlTs.getTime)
}