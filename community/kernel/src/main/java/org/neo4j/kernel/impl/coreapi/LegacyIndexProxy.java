/**
 * Copyright (c) 2002-2014 "Neo Technology,"
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
package org.neo4j.kernel.impl.coreapi;

import org.neo4j.collection.primitive.PrimitiveLongCollections;
import org.neo4j.graphdb.ConstraintViolationException;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.NotFoundException;
import org.neo4j.graphdb.PropertyContainer;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.index.IndexHits;
import org.neo4j.kernel.api.DataWriteOperations;
import org.neo4j.kernel.api.LegacyIndexHits;
import org.neo4j.kernel.api.Statement;
import org.neo4j.kernel.api.exceptions.EntityNotFoundException;
import org.neo4j.kernel.api.exceptions.InvalidTransactionTypeKernelException;
import org.neo4j.kernel.api.exceptions.ReadOnlyDatabaseKernelException;
import org.neo4j.kernel.api.exceptions.legacyindex.LegacyIndexNotFoundKernelException;
import org.neo4j.kernel.impl.core.EntityFactory;
import org.neo4j.kernel.impl.core.ReadOnlyDbException;
import org.neo4j.kernel.impl.core.ThreadToStatementContextBridge;

import static java.lang.String.format;

public class LegacyIndexProxy<T extends PropertyContainer> implements Index<T>
{
    public interface Lookup
    {
        GraphDatabaseService getGraphDatabaseService();

        EntityFactory getEntityFactory();
    }

    public static enum Type
    {
        NODE
        {
            @Override
            Class<Node> getEntityType()
            {
                return Node.class;
            }

            @Override
            Node entity( long id, EntityFactory entityFactory )
            {
                return entityFactory.newNodeProxyById( id );
            }

            @Override
            void add( DataWriteOperations operations, String name, long id, String key, Object value )
                    throws EntityNotFoundException, InvalidTransactionTypeKernelException,
                    ReadOnlyDatabaseKernelException
            {
                operations.nodeAddToLegacyIndex( name, id, key, value );
            }

            @Override
            void remove( DataWriteOperations operations, String name, long id, String key, Object value )
                    throws InvalidTransactionTypeKernelException, ReadOnlyDatabaseKernelException
            {
                operations.nodeRemoveFromLegacyIndex( name, id, key, value );
            }

            @Override
            void remove( DataWriteOperations operations, String name, long id, String key )
                    throws InvalidTransactionTypeKernelException, ReadOnlyDatabaseKernelException
            {
                operations.nodeRemoveFromLegacyIndex( name, id, key );
            }

            @Override
            void remove( DataWriteOperations operations, String name, long id )
                    throws InvalidTransactionTypeKernelException, ReadOnlyDatabaseKernelException
            {
                operations.nodeRemoveFromLegacyIndex( name, id );
            }

            @Override
            void drop( DataWriteOperations operations, String name )
                    throws InvalidTransactionTypeKernelException, ReadOnlyDatabaseKernelException
            {
                operations.nodeLegacyIndexDrop( name );
            }

            @Override
            long putIfAbsent( DataWriteOperations operations, long id, String key, Object value )
                    throws EntityNotFoundException, InvalidTransactionTypeKernelException,
                    ReadOnlyDatabaseKernelException
            {
                return operations.nodeLegacyIndexPutIfAbsent( id, key, value );
            }
        },
        RELATIONSHIP
        {
            @Override
            Class<Relationship> getEntityType()
            {
                return Relationship.class;
            }

            @Override
            Relationship entity( long id, EntityFactory entityFactory )
            {
                return null;
            }

            @Override
            void add( DataWriteOperations operations, String name, long id, String key, Object value )
                    throws EntityNotFoundException, InvalidTransactionTypeKernelException,
                    ReadOnlyDatabaseKernelException
            {
                operations.relationshipAddToLegacyIndex( name, id, key, value );
            }

            @Override
            void remove( DataWriteOperations operations, String name, long id, String key, Object value )
                    throws InvalidTransactionTypeKernelException, ReadOnlyDatabaseKernelException
            {
                operations.relationshipRemoveFromLegacyIndex( name, id, key, value );
            }

            @Override
            void remove( DataWriteOperations operations, String name, long id, String key )
                    throws InvalidTransactionTypeKernelException, ReadOnlyDatabaseKernelException
            {
                operations.relationshipRemoveFromLegacyIndex( name, id, key );
            }

            @Override
            void remove( DataWriteOperations operations, String name, long id )
                    throws InvalidTransactionTypeKernelException, ReadOnlyDatabaseKernelException
            {
                operations.relationshipRemoveFromLegacyIndex( name, id );
            }

            @Override
            void drop( DataWriteOperations operations, String name ) throws InvalidTransactionTypeKernelException,
                    ReadOnlyDatabaseKernelException
            {
                operations.relationshipLegacyIndexDrop( name );
            }

            @Override
            long putIfAbsent( DataWriteOperations operations, long id, String key, Object value )
                    throws EntityNotFoundException, InvalidTransactionTypeKernelException,
                    ReadOnlyDatabaseKernelException
            {
                return operations.relationshipLegacyIndexPutIfAbsent( id, key, value );
            }
        }

        ;

        abstract <T extends PropertyContainer> Class<T> getEntityType();

        abstract <T extends PropertyContainer> T entity( long id, EntityFactory entityFactory );

        abstract void add( DataWriteOperations operations, String name, long id, String key, Object value )
                throws EntityNotFoundException, InvalidTransactionTypeKernelException, ReadOnlyDatabaseKernelException;

        abstract void remove( DataWriteOperations operations, String name, long id, String key, Object value )
                throws InvalidTransactionTypeKernelException, ReadOnlyDatabaseKernelException;

        abstract void remove( DataWriteOperations operations, String name, long id, String key )
                throws InvalidTransactionTypeKernelException, ReadOnlyDatabaseKernelException;

        abstract void remove( DataWriteOperations operations, String name, long id )
                throws InvalidTransactionTypeKernelException, ReadOnlyDatabaseKernelException;

        abstract void drop( DataWriteOperations operations, String name )
                throws InvalidTransactionTypeKernelException, ReadOnlyDatabaseKernelException;

        abstract long putIfAbsent( DataWriteOperations operations, long id, String key, Object value )
                throws EntityNotFoundException, InvalidTransactionTypeKernelException, ReadOnlyDatabaseKernelException;
    }

    private final String name;
    private final Type type;
    private final Lookup lookup;
    private final ThreadToStatementContextBridge statementContextBridge;

    public LegacyIndexProxy( String name, Type type, Lookup lookup,
            ThreadToStatementContextBridge statementContextBridge )
    {
        this.name = name;
        this.type = type;
        this.lookup = lookup;
        this.statementContextBridge = statementContextBridge;
    }

    @Override
    public String getName()
    {
        return name;
    }

    @Override
    public Class<T> getEntityType()
    {
        return type.getEntityType();
    }

    @Override
    public IndexHits<T> get( String key, Object value )
    {
        try ( Statement statement = statementContextBridge.instance() )
        {
            return wrapIndexHits( statement.readOperations().nodeLegacyIndexGet( name, key, value ) );
        }
        catch ( LegacyIndexNotFoundKernelException e )
        {
            throw new NotFoundException( type + " index '" + name + "' doesn't exist" );
        }
    }

    private IndexHits<T> wrapIndexHits( final LegacyIndexHits ids )
    {
        return new IndexHits<T>()
        {
            @Override
            public boolean hasNext()
            {
                return ids.hasNext();
            }

            @Override
            public T next()
            {
                return entityOf( ids.next() );
            }

            @Override
            public void remove()
            {
                throw new UnsupportedOperationException();
            }

            @Override
            public ResourceIterator<T> iterator()
            {
                return this;
            }

            @Override
            public int size()
            {
                return ids.size();
            }

            @Override
            public void close()
            {
                ids.close();
            }

            @Override
            public T getSingle()
            {
                try
                {
                    return entityOf( PrimitiveLongCollections.single( ids ) );
                }
                finally
                {
                    close();
                }
            }

            @Override
            public float currentScore()
            {
                return 0;
            }
        };
    }

    private T entityOf( long id )
    {
        return type.entity( id, lookup.getEntityFactory() );
    }

    @Override
    public IndexHits<T> query( String key, Object queryOrQueryObject )
    {
        try ( Statement statement = statementContextBridge.instance() )
        {
            return wrapIndexHits( statement.readOperations().nodeLegacyIndexQuery( name, key, queryOrQueryObject ) );
        }
        catch ( LegacyIndexNotFoundKernelException e )
        {
            throw new NotFoundException( type + " index '" + name + "' doesn't exist" );
        }
    }

    @Override
    public IndexHits<T> query( Object queryOrQueryObject )
    {
        try ( Statement statement = statementContextBridge.instance() )
        {
            return wrapIndexHits( statement.readOperations().nodeLegacyIndexQuery( name, queryOrQueryObject ) );
        }
        catch ( LegacyIndexNotFoundKernelException e )
        {
            throw new NotFoundException( type + " index '" + name + "' doesn't exist" );
        }
    }

    @Override
    public boolean isWriteable()
    {
        return true;
    }

    @Override
    public GraphDatabaseService getGraphDatabase()
    {
        return lookup.getGraphDatabaseService();
    }

    @Override
    public void add( T entity, String key, Object value )
    {
        try ( Statement statement = statementContextBridge.instance() )
        {
            type.add( statement.dataWriteOperations(), name, entity.getId(), key, value );
        }
        catch ( EntityNotFoundException e )
        {
            throw new NotFoundException( format( "%s %d not found", type, entity.getId() ), e );
        }
        catch ( InvalidTransactionTypeKernelException e )
        {
            throw new ConstraintViolationException( e.getMessage(), e );
        }
        catch ( ReadOnlyDatabaseKernelException e )
        {
            throw new ReadOnlyDbException();
        }
    }

    @Override
    public void remove( T entity, String key, Object value )
    {
        try ( Statement statement = statementContextBridge.instance() )
        {
            type.remove( statement.dataWriteOperations(), name, entity.getId(), key, value );
        }
        catch ( InvalidTransactionTypeKernelException e )
        {
            throw new ConstraintViolationException( e.getMessage(), e );
        }
        catch ( ReadOnlyDatabaseKernelException e )
        {
            throw new ReadOnlyDbException();
        }
    }

    @Override
    public void remove( T entity, String key )
    {
        try ( Statement statement = statementContextBridge.instance() )
        {
            type.remove( statement.dataWriteOperations(), name, entity.getId(), key );
        }
        catch ( InvalidTransactionTypeKernelException e )
        {
            throw new ConstraintViolationException( e.getMessage(), e );
        }
        catch ( ReadOnlyDatabaseKernelException e )
        {
            throw new ReadOnlyDbException();
        }
    }

    @Override
    public void remove( T entity )
    {
        try ( Statement statement = statementContextBridge.instance() )
        {
            type.remove( statement.dataWriteOperations(), name, entity.getId() );
        }
        catch ( InvalidTransactionTypeKernelException e )
        {
            throw new ConstraintViolationException( e.getMessage(), e );
        }
        catch ( ReadOnlyDatabaseKernelException e )
        {
            throw new ReadOnlyDbException();
        }
    }

    @Override
    public void delete()
    {
        try ( Statement statement = statementContextBridge.instance() )
        {
            type.drop( statement.dataWriteOperations(), name );
        }
        catch ( InvalidTransactionTypeKernelException e )
        {
            throw new ConstraintViolationException( e.getMessage(), e );
        }
        catch ( ReadOnlyDatabaseKernelException e )
        {
            throw new ReadOnlyDbException();
        }
    }

    @Override
    public T putIfAbsent( T entity, String key, Object value )
    {
        try ( Statement statement = statementContextBridge.instance() )
        {
            return entityOf( type.putIfAbsent( statement.dataWriteOperations(), entity.getId(), key, value ) );
        }
        catch ( EntityNotFoundException e )
        {
            throw new NotFoundException( format( "%s %d not found", type, entity.getId() ), e );
        }
        catch ( InvalidTransactionTypeKernelException e )
        {
            throw new ConstraintViolationException( e.getMessage(), e );
        }
        catch ( ReadOnlyDatabaseKernelException e )
        {
            throw new ReadOnlyDbException();
        }
    }
}
