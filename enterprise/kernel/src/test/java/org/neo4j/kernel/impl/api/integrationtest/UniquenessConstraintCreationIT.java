/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j Enterprise Edition. The included source
 * code can be redistributed and/or modified under the terms of the
 * GNU AFFERO GENERAL PUBLIC LICENSE Version 3
 * (http://www.fsf.org/licensing/licenses/agpl-3.0.html) with the
 * Commons Clause, as found in the associated LICENSE.txt file.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * Neo4j object code can be licensed independently from the source
 * under separate terms from the AGPL. Inquiries can be directed to:
 * licensing@neo4j.com
 *
 * More information is also available at:
 * https://neo4j.com/licensing/
 */
package org.neo4j.kernel.impl.api.integrationtest;

import org.junit.Test;

import java.util.Iterator;

import org.neo4j.SchemaHelper;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.kernel.api.ReadOperations;
import org.neo4j.kernel.api.SchemaWriteOperations;
import org.neo4j.kernel.api.Statement;
import org.neo4j.kernel.api.StatementTokenNameLookup;
import org.neo4j.kernel.api.TokenWriteOperations;
import org.neo4j.kernel.api.exceptions.KernelException;
import org.neo4j.kernel.api.exceptions.TransactionFailureException;
import org.neo4j.kernel.api.exceptions.schema.ConstraintValidationException;
import org.neo4j.kernel.api.exceptions.schema.CreateConstraintFailureException;
import org.neo4j.kernel.api.exceptions.schema.DropConstraintFailureException;
import org.neo4j.kernel.api.exceptions.schema.NoSuchConstraintException;
import org.neo4j.kernel.api.properties.Property;
import org.neo4j.kernel.api.schema.LabelSchemaDescriptor;
import org.neo4j.kernel.api.schema.SchemaDescriptorFactory;
import org.neo4j.kernel.api.schema.constaints.ConstraintDescriptor;
import org.neo4j.kernel.api.schema.constaints.ConstraintDescriptorFactory;
import org.neo4j.kernel.api.schema.constaints.UniquenessConstraintDescriptor;
import org.neo4j.kernel.api.schema.index.IndexDescriptor;
import org.neo4j.kernel.api.schema.index.IndexDescriptorFactory;
import org.neo4j.kernel.api.security.AnonymousContext;
import org.neo4j.kernel.api.security.SecurityContext;
import org.neo4j.kernel.impl.storageengine.impl.recordstorage.RecordStorageEngine;
import org.neo4j.kernel.impl.store.NeoStores;
import org.neo4j.kernel.impl.store.SchemaStorage;
import org.neo4j.kernel.impl.store.record.ConstraintRule;
import org.neo4j.kernel.impl.store.record.IndexRule;

import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.neo4j.graphdb.Label.label;
import static org.neo4j.helpers.collection.Iterators.asSet;
import static org.neo4j.helpers.collection.Iterators.emptySetOf;
import static org.neo4j.helpers.collection.Iterators.single;

public class UniquenessConstraintCreationIT
        extends AbstractConstraintCreationIT<UniquenessConstraintDescriptor,LabelSchemaDescriptor>
{
    private static final String DUPLICATED_VALUE = "apa";
    private IndexDescriptor uniqueIndex;

    @Override
    int initializeLabelOrRelType( TokenWriteOperations tokenWriteOperations, String name ) throws KernelException
    {
        return tokenWriteOperations.labelGetOrCreateForName( KEY );
    }

    @Override
    UniquenessConstraintDescriptor createConstraint( SchemaWriteOperations writeOps, LabelSchemaDescriptor descriptor )
            throws Exception
    {
        return writeOps.uniquePropertyConstraintCreate( descriptor );
    }

    @Override
    void createConstraintInRunningTx( GraphDatabaseService db, String type, String property )
    {
        SchemaHelper.createUniquenessConstraint( db, type, property );
    }

    @Override
    UniquenessConstraintDescriptor newConstraintObject( LabelSchemaDescriptor descriptor )
    {
        return ConstraintDescriptorFactory.uniqueForSchema( descriptor );
    }

    @Override
    void dropConstraint( SchemaWriteOperations writeOps, UniquenessConstraintDescriptor constraint ) throws Exception
    {
        writeOps.constraintDrop( constraint );
    }

    @Override
    void createOffendingDataInRunningTx( GraphDatabaseService db )
    {
        db.createNode( label( KEY ) ).setProperty( PROP, DUPLICATED_VALUE );
        db.createNode( label( KEY ) ).setProperty( PROP, DUPLICATED_VALUE );
    }

    @Override
    void removeOffendingDataInRunningTx( GraphDatabaseService db )
    {
        try ( ResourceIterator<Node> nodes = db.findNodes( label( KEY ), PROP, DUPLICATED_VALUE ) )
        {
            while ( nodes.hasNext() )
            {
                nodes.next().delete();
            }
        }
    }

    @Override
    LabelSchemaDescriptor makeDescriptor( int typeId, int propertyKeyId )
    {
        uniqueIndex = IndexDescriptorFactory.uniqueForLabel( typeId, propertyKeyId );
        return SchemaDescriptorFactory.forLabel( typeId, propertyKeyId );
    }

    @Test
    public void shouldAbortConstraintCreationWhenDuplicatesExist() throws Exception
    {
        // given
        Statement statement = statementInNewTransaction( AnonymousContext.writeToken() );
        // name is not unique for Foo in the existing data

        int foo = statement.tokenWriteOperations().labelGetOrCreateForName( "Foo" );
        int name = statement.tokenWriteOperations().propertyKeyGetOrCreateForName( "name" );

        long node1 = statement.dataWriteOperations().nodeCreate();

        statement.dataWriteOperations().nodeAddLabel( node1, foo );
        statement.dataWriteOperations().nodeSetProperty( node1, Property.stringProperty( name, "foo" ) );

        long node2 = statement.dataWriteOperations().nodeCreate();
        statement.dataWriteOperations().nodeAddLabel( node2, foo );

        statement.dataWriteOperations().nodeSetProperty( node2, Property.stringProperty( name, "foo" ) );
        commit();

        // when
        LabelSchemaDescriptor descriptor = SchemaDescriptorFactory.forLabel( foo, name );
        try
        {
            SchemaWriteOperations schemaWriteOperations = schemaWriteOperationsInNewTransaction();
            schemaWriteOperations.uniquePropertyConstraintCreate( descriptor );

            fail( "expected exception" );
        }
        // then
        catch ( CreateConstraintFailureException ex )
        {
            assertEquals( ConstraintDescriptorFactory.uniqueForSchema( descriptor ), ex.constraint() );
            Throwable cause = ex.getCause();
            assertThat( cause, instanceOf( ConstraintValidationException.class ) );

            String expectedMessage = String.format(
                    "Both Node(%d) and Node(%d) have the label `Foo` and property `name` = 'foo'", node1, node2 );
            String actualMessage = userMessage( (ConstraintValidationException) cause );
            assertEquals( expectedMessage, actualMessage );
        }
    }

    @Test
    public void shouldCreateAnIndexToGoAlongWithAUniquePropertyConstraint() throws Exception
    {
        // when
        SchemaWriteOperations schemaWriteOperations = schemaWriteOperationsInNewTransaction();
        schemaWriteOperations.uniquePropertyConstraintCreate( descriptor );
        commit();

        // then
        ReadOperations readOperations = readOperationsInNewTransaction();
        assertEquals( asSet( uniqueIndex ), asSet( readOperations.indexesGetAll() ) );
    }

    @Test
    public void shouldDropCreatedConstraintIndexWhenRollingBackConstraintCreation() throws Exception
    {
        // given
        Statement statement = statementInNewTransaction( SecurityContext.AUTH_DISABLED );
        statement.schemaWriteOperations().uniquePropertyConstraintCreate( descriptor );
        assertEquals( asSet( uniqueIndex ), asSet( statement.readOperations().indexesGetAll() ) );

        // when
        rollback();

        // then
        ReadOperations readOperations = readOperationsInNewTransaction();
        assertEquals( emptySetOf( IndexDescriptor.class ), asSet( readOperations.indexesGetAll() ) );
        commit();
    }

    @Test
    public void shouldNotDropUniquePropertyConstraintThatDoesNotExistWhenThereIsAPropertyExistenceConstraint()
            throws Exception
    {
        // given
        SchemaWriteOperations schemaWriteOperations = schemaWriteOperationsInNewTransaction();
        schemaWriteOperations.nodePropertyExistenceConstraintCreate( descriptor );
        commit();

        // when
        try
        {
            SchemaWriteOperations statement = schemaWriteOperationsInNewTransaction();
            statement.constraintDrop( ConstraintDescriptorFactory.uniqueForSchema( descriptor ) );

            fail( "expected exception" );
        }
        // then
        catch ( DropConstraintFailureException e )
        {
            assertThat( e.getCause(), instanceOf( NoSuchConstraintException.class ) );
        }
        finally
        {
            rollback();
        }

        // then
        {
            ReadOperations statement = readOperationsInNewTransaction();

            Iterator<ConstraintDescriptor> constraints = statement.constraintsGetForSchema( descriptor );

            assertEquals( ConstraintDescriptorFactory.existsForSchema( descriptor ), single( constraints ) );
        }
    }

    @Test
    public void committedConstraintRuleShouldCrossReferenceTheCorrespondingIndexRule() throws Exception
    {
        // when
        SchemaWriteOperations statement = schemaWriteOperationsInNewTransaction();
        statement.uniquePropertyConstraintCreate( descriptor );
        commit();

        // then
        SchemaStorage schema = new SchemaStorage( neoStores().getSchemaStore() );
        IndexRule indexRule = schema.indexGetForSchema( IndexDescriptorFactory.uniqueForLabel( typeId, propertyKeyId ) );
        ConstraintRule constraintRule = schema.constraintsGetSingle(
                ConstraintDescriptorFactory.uniqueForLabel( typeId, propertyKeyId ) );
        assertEquals( constraintRule.getId(), indexRule.getOwningConstraint().longValue() );
        assertEquals( indexRule.getId(), constraintRule.getOwnedIndex() );
    }

    private NeoStores neoStores()
    {
        return db.getDependencyResolver().resolveDependency( RecordStorageEngine.class ).testAccessNeoStores();
    }

    @Test
    public void shouldDropConstraintIndexWhenDroppingConstraint() throws Exception
    {
        // given
        Statement statement = statementInNewTransaction( SecurityContext.AUTH_DISABLED );
        UniquenessConstraintDescriptor constraint =
                statement.schemaWriteOperations().uniquePropertyConstraintCreate( descriptor );
        assertEquals( asSet( uniqueIndex ), asSet( statement.readOperations().indexesGetAll() ) );
        commit();

        // when
        SchemaWriteOperations schemaWriteOperations = schemaWriteOperationsInNewTransaction();
        schemaWriteOperations.constraintDrop( constraint );
        commit();

        // then
        ReadOperations readOperations = readOperationsInNewTransaction();
        assertEquals( emptySetOf( IndexDescriptor.class ), asSet( readOperations.indexesGetAll() ) );
        commit();
    }

    private String userMessage( ConstraintValidationException cause )
            throws TransactionFailureException
    {
        StatementTokenNameLookup lookup = new StatementTokenNameLookup( readOperationsInNewTransaction() );
        String actualMessage = cause.getUserMessage( lookup );
        commit();
        return actualMessage;
    }
}