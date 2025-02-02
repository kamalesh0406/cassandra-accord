/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
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

package accord.messages;

import accord.api.ProgressLog.ProgressShard;
import accord.local.*;
import accord.local.Node.Id;
import accord.local.Status.Durability;
import accord.primitives.*;
import accord.topology.Topologies;

import java.util.Collections;

import static accord.api.ProgressLog.ProgressShard.Adhoc;
import static accord.api.ProgressLog.ProgressShard.Home;
import static accord.api.ProgressLog.ProgressShard.Local;
import static accord.local.PreLoadContext.contextFor;
import static accord.messages.SimpleReply.Ok;

public class InformDurable extends TxnRequest<Reply> implements PreLoadContext
{
    public static class SerializationSupport
    {
        public static InformDurable create(TxnId txnId, PartialRoute<?> scope, long waitForEpoch, Timestamp executeAt, Durability durability)
        {
            return new InformDurable(txnId, scope, waitForEpoch, executeAt, durability);
        }
    }

    public final Timestamp executeAt;
    public final Durability durability;
    private transient ProgressShard shard;

    public InformDurable(Id to, Topologies topologies, Route<?> route, TxnId txnId, Timestamp executeAt, Durability durability)
    {
        super(to, topologies, route, txnId);
        this.executeAt = executeAt;
        this.durability = durability;
    }

    private InformDurable(TxnId txnId, PartialRoute<?> scope, long waitForEpoch, Timestamp executeAt, Durability durability)
    {
        super(txnId, scope, waitForEpoch);
        this.executeAt = executeAt;
        this.durability = durability;
    }

    @Override
    public void process()
    {
        Timestamp at = txnId;
        if (progressKey == null)
        {
            // we need to pick a progress log, but this node might not have participated in the coordination epoch
            // in this rare circumstance we simply pick a key to select some progress log to coordinate this
            // TODO (required, consider): We might not replicate either txnId.epoch OR executeAt.epoch, but some inbetween.
            //                            Do we need to receive this message in that case? If so, we need to account for this when selecting a progress key
            at = executeAt;
            progressKey = node.selectProgressKey(executeAt.epoch(), scope, scope.homeKey());
            shard = Adhoc;
        }
        else
        {
            shard = scope.homeKey().equals(progressKey) ? Home : Local;
        }

        // TODO (expected, efficiency): do not load from disk to perform this update
        node.mapReduceConsumeLocal(contextFor(txnId), progressKey, at.epoch(), this);
    }

    @Override
    public Reply apply(SafeCommandStore safeStore)
    {
        Commands.setDurability(safeStore, txnId, durability, scope.homeKey(), executeAt);
        safeStore.progressLog().durable(txnId, scope, shard);
        return Ok;
    }

    @Override
    public Reply reduce(Reply o1, Reply o2)
    {
        throw new IllegalStateException();
    }

    @Override
    public void accept(Reply reply, Throwable failure)
    {
        // TODO: respond with failure
        if (reply == null)
        {
            if (failure == null)
                throw new IllegalStateException("Processed nothing on this node");
            throw new IllegalStateException(failure);
        }
        node.reply(replyTo, replyContext, reply);
    }

    @Override
    public String toString()
    {
        return "InformOfPersistence{" +
               "txnId:" + txnId +
               '}';
    }

    @Override
    public MessageType type()
    {
        return MessageType.INFORM_DURABLE_REQ;
    }

    @Override
    public Iterable<TxnId> txnIds()
    {
        return Collections.singleton(txnId);
    }

    @Override
    public Seekables<?, ?> keys()
    {
        return Keys.EMPTY;
    }
}
