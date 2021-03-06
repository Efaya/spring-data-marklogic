/*
 * Copyright 2002-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.data.marklogic.datasource;

import com.marklogic.xcc.Session;

/**
 * Simple interface to be implemented by handles for a XDBC Session.
 *
 * @author Juergen Hoeller
 * @author Stephane Toussaint
 * @see SimpleSessionHandle
 * @see SessionHolder
 */
public interface SessionHandle {

	/**
	 * Fetch the XDBC Session that this handle refers to.
	 */
	Session getSession();

	/**
	 * Release the XDBC Session that this handle refers to.
	 * @param ses the XDBC Session to release
	 */
	void releaseSession(Session ses);

}
