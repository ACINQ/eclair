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

package fr.acinq.eclair;

import fr.acinq.bitcoin.scalacompat.Satoshi;

/**
 * This class is a compile-time check that we are able to compile Java code that uses MilliSatoshi utilities.
 */
public final class MilliSatoshiTest {

    public static void Test() {
        MilliSatoshi msat = new MilliSatoshi(561);
        Satoshi sat = new Satoshi(1);
        msat.truncateToSatoshi();
        msat = msat.max(sat);
        msat = msat.min(sat);
        MilliSatoshi.toMilliSatoshi(sat);
        msat = MilliSatoshi.toMilliSatoshi(sat).$plus(msat);
        msat = msat.$plus(msat);
        msat = msat.$times(2.0);
        Boolean check1 = msat.$less$eq(new MilliSatoshi(1105));
        Boolean check2 = msat.$greater(sat);
    }

}
