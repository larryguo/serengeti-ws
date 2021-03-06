/***************************************************************************
 * Copyright (c) 2012-2013 VMware, Inc. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ***************************************************************************/

package org.springframework.data.hadoop.impala.provider;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.shell.plugin.HistoryFileNameProvider;
import org.springframework.stereotype.Component;

/**
 * history file name provider to customize Spring Shell's log file 
 * 
 * @author Jarred Li
 *
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ImpalaPluginHistoryFileNameProvider implements HistoryFileNameProvider {

	public String getHistoryFileName() {
		return "impala-cli.log";
	}

	public String name() {
		return "Impala Cli History File Name Provider";
	}
}
