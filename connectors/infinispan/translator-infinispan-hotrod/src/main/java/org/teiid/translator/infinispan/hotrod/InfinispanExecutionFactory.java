/*
 * Copyright Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags and
 * the COPYRIGHT.txt file distributed with this work.
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

package org.teiid.translator.infinispan.hotrod;

import java.util.ArrayList;
import javax.resource.cci.ConnectionFactory;

import org.teiid.core.util.PropertiesUtils;
import org.teiid.infinispan.api.InfinispanConnection;
import org.teiid.infinispan.api.ProtobufResource;
import org.teiid.language.Command;
import org.teiid.language.QueryExpression;
import org.teiid.metadata.MetadataFactory;
import org.teiid.metadata.RuntimeMetadata;
import org.teiid.metadata.Schema;
import org.teiid.metadata.Table;
import org.teiid.translator.ExecutionContext;
import org.teiid.translator.ExecutionFactory;
import org.teiid.translator.MetadataProcessor;
import org.teiid.translator.ResultSetExecution;
import org.teiid.translator.Translator;
import org.teiid.translator.TranslatorException;
import org.teiid.translator.TranslatorProperty;
import org.teiid.translator.UpdateExecution;


@Translator(name = "infinispan-hotrod", description = "The Infinispan Translator Using Protobuf & Hotrod")
public class InfinispanExecutionFactory extends ExecutionFactory<ConnectionFactory, InfinispanConnection>{
	public static final int MAX_SET_SIZE = 1024;

	private boolean supportsCompareCriteriaOrdered = true;
	private boolean supportsUpsert = true;

	public InfinispanExecutionFactory() {
		setMaxInCriteriaSize(MAX_SET_SIZE);
		setMaxDependentInPredicates(MAX_SET_SIZE);
		setSupportsOrderBy(true);
		setSupportsSelectDistinct(false);
		setSupportsInnerJoins(true);
		setSupportsFullOuterJoins(true);
		setSupportsOuterJoins(true);
		setSupportedJoinCriteria(SupportedJoinCriteria.KEY);
		setTransactionSupport(TransactionSupport.NONE);
	}

    @Override
    public void start() throws TranslatorException {
        super.start();
    }

    @Override
    public ResultSetExecution createResultSetExecution(QueryExpression command,
            ExecutionContext executionContext, RuntimeMetadata metadata,
            InfinispanConnection connection) throws TranslatorException {
        return new InfinispanQueryExecution(this, command, executionContext, metadata, connection);
    }

    @Override
    public UpdateExecution createUpdateExecution(Command command,
            ExecutionContext executionContext, RuntimeMetadata metadata,
            InfinispanConnection connection) throws TranslatorException {
        return new InfinispanUpdateExecution(command, executionContext, metadata,
                connection);
    }

    @Override
    public void getMetadata(MetadataFactory metadataFactory, InfinispanConnection conn) throws TranslatorException {
        ProtobufMetadataProcessor metadataProcessor = (ProtobufMetadataProcessor)getMetadataProcessor();
        PropertiesUtils.setBeanProperties(metadataProcessor, metadataFactory.getModelProperties(), "importer"); //$NON-NLS-1$

        // tables are provided through other metadata repositories
        Schema schema = metadataFactory.getSchema();
        ProtobufResource resource = null;
        if (schema.getTables() != null && !schema.getTables().isEmpty()) {
            SchemaToProtobufProcessor stpp = new SchemaToProtobufProcessor();
            stpp.setIndexMessages(true);
            resource = stpp.process(metadataFactory, conn);
            metadataProcessor.setProtobufResource(resource);
            ArrayList<Table> tables = new ArrayList<>(schema.getTables().values());
            for (Table t : tables) {
                // remove the previous tables, as we want to introduce them with necessary
                // extension metadata generated with generated .proto file. As some of the default
                // extension metadata can be added.
                schema.removeTable(t.getName());
            }
        }

        metadataProcessor.process(metadataFactory, conn);

        resource = metadataProcessor.getProtobufResource();
        if(resource == null) {
            SchemaToProtobufProcessor stpp = new SchemaToProtobufProcessor();
            resource = stpp.process(metadataFactory, conn);
        }
        // register protobuf
        if (resource != null) {
            conn.registerProtobufFile(resource);
        }
    }

    @Override
    public MetadataProcessor<InfinispanConnection> getMetadataProcessor() {
        return new ProtobufMetadataProcessor();
    }

    @Override
    public boolean isSourceRequiredForCapabilities() {
        return true;
    }

    @Override
    public boolean supportsAliasedTable() {
        return true;
    }

    @Override
    public boolean supportsInCriteria() {
        return true;
    }

    @Override
    public boolean supportsIsNullCriteria() {
        return false;
    }

    @Override
    public boolean supportsOrCriteria() {
        return true;
    }

    @Override
    public boolean supportsCompareCriteriaEquals() {
        return true;
    }

    @TranslatorProperty(display="CompareCriteriaOrdered", description="If true, translator can support comparison criteria with the operator '=>' or '<=' ",advanced=true)
    @Override
    public boolean supportsCompareCriteriaOrdered() {
        return supportsCompareCriteriaOrdered;
    }

    public boolean setSupportsCompareCriteriaOrdered(boolean supports) {
        return supportsCompareCriteriaOrdered = supports;
    }

    @TranslatorProperty(display="Upsert", description="If true, translator can support Upsert command",advanced=true)
    @Override
    public boolean supportsUpsert() {
        return supportsUpsert;
    }

    public boolean setSupportsUpsert(boolean supports) {
        return supportsUpsert = supports;
    }

    @Override
    public boolean supportsLikeCriteria() {
        return true;
    }

    @Override
    public boolean supportsLikeCriteriaEscapeCharacter() {
        return true;
    }

    @Override
    public boolean supportsNotCriteria() {
        return false;
    }

    @Override
    public boolean supportsGroupBy() {
        return true;
    }

    @Override
    public boolean supportsRowLimit() {
        return true;
    }

    @Override
    public boolean supportsRowOffset() {
        return true;
    }

    @Override
    public boolean useAnsiJoin() {
        return true;
    }

    @Override
    public boolean supportsOnlyLiteralComparison() {
        return true;
    }
    @Override
    public boolean supportsAggregatesSum() {
        return false;
    }

    @Override
    public boolean supportsAggregatesAvg() {
        return true;
    }

    @Override
    public boolean supportsAggregatesMin() {
        return true;
    }

    @Override
    public boolean supportsAggregatesMax() {
        return true;
    }

    @Override
    public boolean supportsAggregatesCount() {
        return true;
    }

    @Override
    public boolean supportsHaving() {
        return true;
    }
}
