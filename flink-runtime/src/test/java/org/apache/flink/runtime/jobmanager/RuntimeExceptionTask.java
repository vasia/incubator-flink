/**
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


package org.apache.flink.runtime.jobmanager;

import org.apache.flink.runtime.jobgraph.tasks.AbstractInvokable;

/**
 * This task throws a {@link RuntimeException} when the method <code>registerInputOutput</code> is called.
 */
public class RuntimeExceptionTask extends AbstractInvokable {

	/**
	 * The message which is used for the test runtime exception.
	 */
	public static final String RUNTIME_EXCEPTION_MESSAGE = "This is a test runtime exception";


	@Override
	public void registerInputOutput() {
		throw new RuntimeException(RUNTIME_EXCEPTION_MESSAGE);
	}

	@Override
	public void invoke() {}
}
