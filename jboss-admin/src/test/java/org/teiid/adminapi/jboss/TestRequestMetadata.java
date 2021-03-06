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

package org.teiid.adminapi.jboss;

import static org.junit.Assert.assertEquals;

import org.jboss.dmr.ModelNode;
import org.junit.Test;
import org.teiid.adminapi.Request.ProcessingState;
import org.teiid.adminapi.Request.ThreadState;
import org.teiid.adminapi.impl.RequestMetadata;
import org.teiid.adminapi.jboss.VDBMetadataMapper;
@SuppressWarnings("nls")
public class TestRequestMetadata {
	
	@Test public void testMapping() {
		RequestMetadata request = buildRequest();
		
		ModelNode node = VDBMetadataMapper.RequestMetadataMapper.INSTANCE.wrap(request, new ModelNode());
		
		RequestMetadata actual = VDBMetadataMapper.RequestMetadataMapper.INSTANCE.unwrap(node);
		
		assertEquals(request, actual);
		assertEquals(request.getState(), actual.getState());
	}

	private RequestMetadata buildRequest() {
		RequestMetadata request = new RequestMetadata();
		request.setState(ProcessingState.PROCESSING);
		request.setCommand("select * from foo"); //$NON-NLS-1$
		request.setExecutionId(1234);
		request.setName("request-name"); //$NON-NLS-1$
		request.setSessionId("session-id");//$NON-NLS-1$
		request.setSourceRequest(false);
		request.setStartTime(12345L);
		request.setTransactionId("transaction-id");//$NON-NLS-1$
		request.setThreadState(ThreadState.RUNNING);
		//request.setNodeId(1);
		return request;
	}
	
	public static final String desc = "{\n" + 
			"    \"execution-id\" : {\n" + 
			"        \"type\" : {\n" + 
			"            \"TYPE_MODEL_VALUE\" : \"LONG\"\n" + 
			"        },\n" + 
			"        \"description\" : \"Unique Identifier for Request\",\n" + 
			"        \"required\" : true\n" + 
			"    },\n" + 
			"    \"session-id\" : {\n" + 
			"        \"type\" : {\n" + 
			"            \"TYPE_MODEL_VALUE\" : \"STRING\"\n" + 
			"        },\n" + 
			"        \"description\" : \"Session Identifier\",\n" + 
			"        \"required\" : true\n" + 
			"    },\n" + 
			"    \"start-time\" : {\n" + 
			"        \"type\" : {\n" + 
			"            \"TYPE_MODEL_VALUE\" : \"LONG\"\n" + 
			"        },\n" + 
			"        \"description\" : \"Start time for the request\",\n" + 
			"        \"required\" : true\n" + 
			"    },\n" + 
			"    \"command\" : {\n" + 
			"        \"type\" : {\n" + 
			"            \"TYPE_MODEL_VALUE\" : \"STRING\"\n" + 
			"        },\n" + 
			"        \"description\" : \"Executing Command\",\n" + 
			"        \"required\" : true\n" + 
			"    },\n" + 
			"    \"source-request\" : {\n" + 
			"        \"type\" : {\n" + 
			"            \"TYPE_MODEL_VALUE\" : \"BOOLEAN\"\n" + 
			"        },\n" + 
			"        \"description\" : \"Is this Connector level request\",\n" + 
			"        \"required\" : true\n" + 
			"    },\n" + 
			"    \"node-id\" : {\n" + 
			"        \"type\" : {\n" + 
			"            \"TYPE_MODEL_VALUE\" : \"INT\"\n" + 
			"        },\n" + 
			"        \"description\" : \"Node Identifier\",\n" + 
			"        \"required\" : false\n" + 
			"    },\n" + 
			"    \"transaction-id\" : {\n" + 
			"        \"type\" : {\n" + 
			"            \"TYPE_MODEL_VALUE\" : \"STRING\"\n" + 
			"        },\n" + 
			"        \"description\" : \"Get Transaction XID if transaction involved\",\n" + 
			"        \"required\" : false\n" + 
			"    },\n" + 
			"    \"processing-state\" : {\n" + 
			"        \"type\" : {\n" + 
			"            \"TYPE_MODEL_VALUE\" : \"STRING\"\n" + 
			"        },\n" + 
			"        \"description\" : \"State of the Request\",\n" + 
			"        \"required\" : true\n" + 
			"    },\n" + 
			"    \"thread-state\" : {\n" + 
			"        \"type\" : {\n" + 
			"            \"TYPE_MODEL_VALUE\" : \"STRING\"\n" + 
			"        },\n" + 
			"        \"description\" : \"Thread state\",\n" + 
			"        \"required\" : true\n" + 
			"    }\n" + 
			"}";
	@Test public void testDescribe() {
		assertEquals(desc, VDBMetadataMapper.RequestMetadataMapper.INSTANCE.describe(new ModelNode()).toJSONString(false));
	}

}
