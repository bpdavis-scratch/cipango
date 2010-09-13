// ========================================================================
// Copyright 2010 NEXCOM Systems
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at 
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================
package org.cipango.diameter.router;

import org.cipango.diameter.node.DiameterRequest;
import org.cipango.diameter.node.Node;
import org.cipango.diameter.node.Peer;


public class DefaultRouter implements DiameterRouter
{

	private Node _node;
	
	public Peer getRoute(DiameterRequest request)
	{
		return _node.getPeer(request.getDestinationHost());
	}

	public Node getNode()
	{
		return _node;
	}

	public void setNode(Node node)
	{
		_node = node;
	}

	public void peerAdded(Peer peer)
	{
	}

	public void peerRemoved(Peer peer)
	{
	}

}
