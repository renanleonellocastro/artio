/*
 * Copyright 2015 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.fix_gateway.generic_callback_api;

import uk.co.real_logic.agrona.DirectBuffer;

public interface FixMessageAcceptor
{
    int NEW_ORDER_SINGLE = 'D';
    char SELL = '2';
    int SIDE = 54;
    int SYMBOL = 55;

    void onStartMessage(long connectionId);

    void onField(int tag, DirectBuffer buffer, int offset, int length);

    /**
     * Called at the beginning of a repeating group.
     *
     * @param tag the tag number of the field representing the number of elements, eg NoAllocs
     * @param numberOfElements the number of group elements repeated
     */
    void onGroupHeader(int tag, int numberOfElements);

    /**
     * Called at the beginning of each group entry.
     *
     * @param tag
     * @param numberOfElements
     * @param index
     */
    void onGroupBegin(int tag, int numberOfElements, int index);

    /**
     * Called at the end of each group entry
     *
     * @param tag
     * @param numberOfElements
     * @param index
     */
    void onGroupEnd(int tag, int numberOfElements, int index);

    void onEndMessage(boolean passedChecksum);

}
