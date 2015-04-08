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
package uk.co.real_logic.fix_gateway.system_tests;

import quickfix.*;

import java.util.ArrayList;
import java.util.List;

public class FakeQuickFixApplication implements Application
{
    private final List<SessionID> logons = new ArrayList<>();

    public void onCreate(final SessionID sessionID)
    {

    }

    public void onLogon(final SessionID sessionID)
    {
        logons.add(sessionID);
    }

    public void onLogout(final SessionID sessionID)
    {

    }

    public void toAdmin(final Message message, final SessionID sessionID)
    {

    }

    public void fromAdmin(final Message message, final SessionID sessionID)
        throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon
    {

    }

    public void toApp(final Message message, final SessionID sessionID) throws DoNotSend
    {

    }

    public void fromApp(final Message message, final SessionID sessionID)
        throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType
    {

    }

    public List<SessionID> logons()
    {
        return logons;
    }
}
