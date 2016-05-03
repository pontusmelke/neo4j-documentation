/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.api;

import java.util.function.Consumer;

import org.neo4j.collection.primitive.Primitive;
import org.neo4j.collection.primitive.PrimitiveLongIterator;
import org.neo4j.collection.primitive.PrimitiveLongSet;
import org.neo4j.cursor.Cursor;
import org.neo4j.function.ThrowingConsumer;
import org.neo4j.kernel.api.exceptions.EntityNotFoundException;
import org.neo4j.kernel.api.exceptions.KernelException;
import org.neo4j.kernel.impl.api.operations.EntityReadOperations;
import org.neo4j.kernel.impl.api.store.RelationshipIterator;
import org.neo4j.kernel.impl.locking.ResourceTypes;
import org.neo4j.storageengine.api.Direction;
import org.neo4j.storageengine.api.NodeItem;

class TwoPhaseNodeForRelationshipLocking
{
    private boolean retry = true;
    private long firstRelId = -1;
    private final PrimitiveLongSet nodeIds = Primitive.longSet();

    private final EntityReadOperations reads;
    private final ThrowingConsumer<Long,KernelException> relIdAction;

    private final RelationshipVisitor<RuntimeException> collectNodeIdVisitor =
            new RelationshipVisitor<RuntimeException>()
            {
                @Override
                public void visit( long relId, int type, long startNode, long endNode )
                {
                    if ( firstRelId == -1 )
                    {
                        firstRelId = relId;
                    }
                    nodeIds.add( startNode );
                    nodeIds.add( endNode );
                }
            };

    private boolean first = true;
    private final RelationshipVisitor<KernelException> relationshipConsumingVisitor =
            new RelationshipVisitor<KernelException>()
            {
                @Override
                public void visit( long relId, int type, long startNode, long endNode ) throws KernelException
                {
                    if ( first )
                    {
                        first = false;
                        if ( relId != firstRelId )
                        {
                            // if the first relationship is not the same someone added some new rels, so we need to
                            // lock them all again
                            retry = true;
                            return;
                        }
                    }

                    relIdAction.accept( relId );
                }
            };

    TwoPhaseNodeForRelationshipLocking( EntityReadOperations reads, ThrowingConsumer<Long,KernelException> relIdAction )
    {
        this.reads = reads;
        this.relIdAction = relIdAction;
    }

    void lockAllNodesAndConsumeRelationships( long nodeId, final KernelStatement state ) throws KernelException
    {
        while ( retry )
        {
            retry = false;

            // lock all the nodes involved by following the node id ordering
            try ( Cursor<NodeItem> cursor = reads.nodeCursorById( state, nodeId ) )
            {
                RelationshipIterator relationships = cursor.get().getRelationships( Direction.BOTH );
                while ( relationships.hasNext() )
                {
                    reads.relationshipVisit( state, relationships.next(), collectNodeIdVisitor );
                }
            }

            {
                PrimitiveLongIterator iterator = nodeIds.iterator();
                while ( iterator.hasNext() )
                {
                    state.locks().acquireExclusive( ResourceTypes.NODE, iterator.next() );
                }
            }

            // perform the action on each relationship, we will retry if the the relationship iterator contains new relationships
            try ( Cursor<NodeItem> cursor = reads.nodeCursorById( state, nodeId ) )
            {
                RelationshipIterator relationships = cursor.get().getRelationships( Direction.BOTH );
                while ( relationships.hasNext() )
                {
                    reads.relationshipVisit( state, relationships.next(), relationshipConsumingVisitor );
                    if ( retry )
                    {
                        PrimitiveLongIterator iterator = nodeIds.iterator();
                        while ( iterator.hasNext() )
                        {
                            state.locks().releaseExclusive( ResourceTypes.NODE, iterator.next() );
                        }
                        nodeIds.clear();
                        break;
                    }
                }
            }
        }
    }
}
