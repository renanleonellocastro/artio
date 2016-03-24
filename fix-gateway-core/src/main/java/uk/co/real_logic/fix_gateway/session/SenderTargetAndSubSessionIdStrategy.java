/*
 * Copyright 2015-2016 Real Logic Ltd.
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
package uk.co.real_logic.fix_gateway.session;

import uk.co.real_logic.agrona.DirectBuffer;
import uk.co.real_logic.agrona.MutableDirectBuffer;
import uk.co.real_logic.fix_gateway.builder.HeaderEncoder;
import uk.co.real_logic.fix_gateway.decoder.HeaderDecoder;
import uk.co.real_logic.fix_gateway.dictionary.generation.CodecUtil;
import uk.co.real_logic.fix_gateway.messages.SenderTargetAndSubCompositeKeyDecoder;
import uk.co.real_logic.fix_gateway.messages.SenderTargetAndSubCompositeKeyEncoder;

import java.util.Arrays;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * A simple, and dumb session id Strategy based upon hashing SenderCompID and TargetCompID. Makes no assumptions
 * about the nature of either identifiers.
 *
 * Threadsafe - no state;
 */
public class SenderTargetAndSubSessionIdStrategy implements SessionIdStrategy
{
    private static final int BLOCK_AND_LENGTH_FIELDS_LENGTH = SenderTargetAndSubCompositeKeyEncoder.BLOCK_LENGTH + 6;

    private final SenderTargetAndSubCompositeKeyEncoder keyEncoder = new SenderTargetAndSubCompositeKeyEncoder();
    private final SenderTargetAndSubCompositeKeyDecoder keyDecoder = new SenderTargetAndSubCompositeKeyDecoder();
    private final int actingBlockLength = keyDecoder.sbeBlockLength();
    private final int actingVersion = keyDecoder.sbeSchemaVersion();

    public CompositeKey onLogon(final HeaderDecoder header)
    {
        return new CompositeKeyImpl(
            header.targetCompID(), header.targetCompIDLength(),
            header.targetSubID(), header.targetSubIDLength(),
            header.senderCompID(), header.senderCompIDLength());
    }

    public CompositeKey onLogon(
        final String senderCompId, final String senderSubId, final String senderLocationId, final String targetCompId)
    {
        final char[] senderCompIdChars = senderCompId.toCharArray();
        final char[] senderSubIdChars = senderSubId.toCharArray();
        final char[] targetCompIdChars = targetCompId.toCharArray();
        return new CompositeKeyImpl(
            senderCompIdChars,
            senderCompIdChars.length,
            senderSubIdChars,
            senderSubIdChars.length,
            targetCompIdChars,
            targetCompIdChars.length);
    }

    public void setupSession(final CompositeKey compositeKey, final HeaderEncoder headerEncoder)
    {
        final CompositeKeyImpl composite = (CompositeKeyImpl) compositeKey;
        headerEncoder.senderCompID(composite.senderCompID);
        headerEncoder.senderSubID(composite.senderSubID);
        headerEncoder.targetCompID(composite.targetCompID);
    }

    public int save(final CompositeKey compositeKey, final MutableDirectBuffer buffer, final int offset)
    {
        final CompositeKeyImpl key = (CompositeKeyImpl) compositeKey;
        final byte[] senderCompID = key.senderCompID;
        final byte[] senderSubID = key.senderSubID;
        final byte[] targetCompID = key.targetCompID;

        final int length =
            senderCompID.length + senderSubID.length + targetCompID.length + BLOCK_AND_LENGTH_FIELDS_LENGTH;

        if (buffer.capacity() < offset + length)
        {
            return INSUFFICIENT_SPACE;
        }

        keyEncoder.wrap(buffer, offset);
        keyEncoder.putSenderCompId(senderCompID, 0, senderCompID.length);
        keyEncoder.putSenderSubId(senderSubID, 0, senderSubID.length);
        keyEncoder.putTargetCompId(targetCompID, 0, targetCompID.length);

        return length;
    }

    public CompositeKey load(final DirectBuffer buffer, final int offset, final int length)
    {
        keyDecoder.wrap(buffer, offset, actingBlockLength, actingVersion);

        final int senderCompIdLength = keyDecoder.senderCompIdLength();
        final byte[] senderCompId = new byte[senderCompIdLength];
        keyDecoder.getSenderCompId(senderCompId, 0, senderCompIdLength);

        final int senderSubIdLength = keyDecoder.senderSubIdLength();
        final byte[] senderSubId = new byte[senderSubIdLength];
        keyDecoder.getSenderSubId(senderSubId, 0, senderSubIdLength);

        final int targetCompIdLength = keyDecoder.targetCompIdLength();
        final byte[] targetCompId = new byte[targetCompIdLength];
        keyDecoder.getTargetCompId(targetCompId, 0, targetCompIdLength);

        return new CompositeKeyImpl(senderCompId, senderSubId, targetCompId);
    }

    private static final class CompositeKeyImpl implements CompositeKey
    {
        private final byte[] senderCompID;
        private final byte[] senderSubID;
        private final byte[] targetCompID;
        private final int hashCode;

        private CompositeKeyImpl(
            final char[] senderCompID,
            final int senderCompIDLength,
            final char[] senderSubID,
            final int senderSubIDLength,
            final char[] targetCompID,
            final int targetCompIDLength)
        {
            this(
                CodecUtil.toBytes(senderCompID, senderCompIDLength),
                CodecUtil.toBytes(senderSubID, senderSubIDLength),
                CodecUtil.toBytes(targetCompID, targetCompIDLength));
        }

        private CompositeKeyImpl(
            final byte[] senderCompID,
            final byte[] senderSubID,
            final byte[] targetCompID)
        {
            this.senderCompID = senderCompID;
            this.senderSubID = senderSubID;
            this.targetCompID = targetCompID;
            hashCode = hash(this.senderCompID, this.senderSubID, this.targetCompID);
        }

        private int hash(final byte[] senderCompID, final byte[] senderSubID, final byte[] targetCompID)
        {
            int result  = Arrays.hashCode(senderCompID);
            result = 31 * result + Arrays.hashCode(senderSubID);
            result = 31 * result + Arrays.hashCode(targetCompID);
            return result;
        }

        public int hashCode()
        {
            return hashCode;
        }

        public boolean equals(final Object obj)
        {
            if (obj instanceof CompositeKeyImpl)
            {
                final CompositeKeyImpl compositeKey = (CompositeKeyImpl)obj;
                return Arrays.equals(compositeKey.senderCompID, senderCompID)
                    && Arrays.equals(compositeKey.senderSubID, senderSubID)
                    && Arrays.equals(compositeKey.targetCompID, targetCompID);
            }

            return false;
        }

        public String toString()
        {
            return "CompositeKey{" +
                "senderCompID=" + Arrays.toString(senderCompID) +
                "senderSubID=" + Arrays.toString(senderSubID) +
                ", targetCompID=" + Arrays.toString(targetCompID) +
                '}';
        }

        public String senderCompId()
        {
            return new String(senderCompID, US_ASCII);
        }

        public String senderSubId()
        {
            return new String(senderSubID, US_ASCII);
        }

        public String senderLocationId()
        {
            return "";
        }

        public String targetCompId()
        {
            return new String(targetCompID, US_ASCII);
        }
    }
}
