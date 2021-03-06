// ========================================================================
// Copyright 2008-2009 NEXCOM Systems
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

package org.cipango.server.session;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletResponse;

import org.cipango.server.ID;
import org.cipango.server.SipHandler;
import org.cipango.server.SipMessage;
import org.cipango.server.SipRequest;
import org.cipango.server.SipResponse;
import org.cipango.server.transaction.ServerTransaction;
import org.cipango.servlet.SipServletHandler;
import org.cipango.servlet.SipServletHolder;
import org.cipango.sip.SipException;
import org.cipango.sip.SipParams;
import org.cipango.sipapp.SipAppContext;
import org.cipango.util.ExceptionUtil;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Handles incoming messages in the appropriate SipSession context.
 *
 */
public class SipSessionHandler extends AbstractHandler implements SipHandler
{
	private static final Logger LOG = Log.getLogger(SipSessionHandler.class);
	
	public void handle(String target, Request baseRequest, HttpServletRequest request,
			HttpServletResponse response) throws IOException, ServletException 
	{
		throw new UnsupportedOperationException("sip-only handler");
	}
	
	public void handle(SipServletMessage message) throws IOException, ServletException 
	{
		if (((SipMessage) message).isRequest())
			handleRequest((SipRequest) message);
	}
	
	public void handleRequest(SipRequest request) throws IOException, ServletException
	{
		Session session = null;
		
		if (request.isInitial())
		{
			SipAppContext appContext = (SipAppContext) request.getHandlerAttribute(ID.CONTEXT_ATTRIBUTE);
			SipServletHolder handler = ((SipServletHandler) appContext.getServletHandler()).findHolder(request);
	
			if (handler == null)
			{
				LOG.debug("SIP application {} has no matching servlet for {}", appContext.getName(), request.getMethod());
				if (!request.isAck())
				{						
					SipResponse response = (SipResponse) request.createResponse(SipServletResponse.SC_NOT_FOUND);
					response.to().setParameter(SipParams.TAG, ID.newTag());
					((ServerTransaction) request.getTransaction()).send(response);
				}
				return;
			}
			
			AppSession appSession;
			
			String key = (String) request.getHandlerAttribute(ID.SESSION_KEY_ATTRIBUTE);
			
			if (key != null)
			{
				String id = ID.getIdFromKey(appContext.getName(), key);
				appSession = request.getCallSession().getAppSession(id);
				if (appSession == null)
					appSession = request.getCallSession().createAppSession(appContext, id);
			}
			else
			{
				appSession = request.getCallSession().createAppSession(appContext, ID.newAppSessionId());
			}
				
			session = appSession.createSession();
			session.setHandler(handler);
        
			session.setSubscriberURI(request.getSubscriberURI());
			session.setRegion(request.getRegion());
		
	        if (LOG.isDebugEnabled())
	            LOG.debug("new session {}", session);
		}
		else
		{
			session = request.getCallSession().findSession(request);
			
			if (session == null) 
            {
				if (!request.isAck()) 
                {
					SipResponse response = (SipResponse) request.createResponse(SipServletResponse.SC_CALL_LEG_DONE);
					((ServerTransaction) request.getTransaction()).send(response);
				}
				return;
			}
		}
		if (request.isInvite()) 
        { 
			SipResponse response = (SipResponse) request.createResponse(SipServletResponse.SC_TRYING);
			((ServerTransaction) request.getTransaction()).send(response);
		}
		
		request.setSession(session);
        
        try
        {
            session.handleRequest(request);
        }
        catch (Exception e)
        {
        	if (!request.isAck() && !request.isCommitted())
        	{
        		int code = SipServletResponse.SC_SERVER_INTERNAL_ERROR;
        		if (e instanceof SipException)
        			code = ((SipException) e).getCode();
        		
        		SipServletResponse response;
        		if (code == SipServletResponse.SC_SERVER_INTERNAL_ERROR)
        		{
        			response = request.createResponse(
    	        			SipServletResponse.SC_SERVER_INTERNAL_ERROR,
    	        			"Error in handler: " + e.getMessage());
        			ExceptionUtil.fillStackTrace(response, e);
        		}
        		else
        		{
        			response = request.createResponse(code);
        		}
	        	response.send();
        	}
        	else
        	{
        		LOG.debug(e);
        	}
        }
	}
}
