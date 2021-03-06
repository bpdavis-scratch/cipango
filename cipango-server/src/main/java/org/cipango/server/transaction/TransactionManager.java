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

package org.cipango.server.transaction;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletResponse;

import org.cipango.server.SipHandler;
import org.cipango.server.SipMessage;
import org.cipango.server.SipProxy;
import org.cipango.server.SipRequest;
import org.cipango.server.SipResponse;
import org.cipango.server.transaction.Transaction.TimersSettings;
import org.cipango.sip.SipGrammar;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.statistic.CounterStatistic;

public class TransactionManager extends HandlerWrapper implements SipHandler
{      
	private static final Logger LOG = Log.getLogger(TransactionManager.class);
	
    private CounterStatistic _retransStats = new CounterStatistic();
    private CounterStatistic _notFoundStats = new CounterStatistic();
	
    protected TimersSettings _timersSettings = new TimersSettings();
    
	public void handle(SipServletMessage message) throws ServletException, IOException 
    {
		if (((SipMessage) message).isRequest())
			handleRequest((SipRequest) message);
		else
			handleResponse((SipResponse) message);
	}
	
	public void handleRequest(SipRequest request) throws ServletException, IOException 
    {
		String branch = request.getTopVia().getBranch();
		
		if (branch == null || !branch.startsWith(SipGrammar.MAGIC_COOKIE)) 
        {
			if (!("0".equals(branch) && request.isAck()))
			{
				LOG.debug("Not 3261 branch: {}. Dropping request", branch);
				return;
			}
		}
		if (request.isCancel()) 
			branch = "cancel-" + branch;
		
		ServerTransaction transaction = request.getCallSession().getServerTransaction(branch);
		
		if (transaction != null) 
        {
            if (LOG.isDebugEnabled()) 
                LOG.debug("request {} in transaction {}", request.getRequestLine(), transaction);
			
            request.setTransaction(transaction);
            if (request.isAck())
            {
            	transaction.handleAck(request);
            }
            else
            {
            	retransReceived();
            	transaction.handleRetransmission(request);
            }
		} 
		else
		{
			transaction = new ServerTransaction(request);

			if (!request.isAck()) 
				request.getCallSession().addServerTransaction(transaction);
		 
	        if (LOG.isDebugEnabled())
	            LOG.debug("new transaction {} for request {}", transaction, request.getRequestLine());
	
	        // TODO move to Session
			if (request.isCancel())
	        {
	            String txBranch = request.getTopVia().getBranch();
	            ServerTransaction stx = request.getCallSession().getServerTransaction(txBranch);
	            if (stx == null)
	            {
	                if (LOG.isDebugEnabled())
	                    LOG.debug("No transaction for cancelled branch {}", txBranch, null);
	                SipResponse unknown = (SipResponse) request.createResponse(SipServletResponse.SC_CALL_LEG_DONE);
	                transaction.send(unknown);
	            }
	            else 
	            {	                
	                stx.cancel(request);
	            }
	        }
	        else 
	        	((SipHandler) getHandler()).handle(request);
		}
	}
	
	public void handleResponse(SipResponse response) throws ServletException, IOException
    {
		String branch = response.getTopVia().getBranch();
        
		if (response.isCancel()) 
			branch = "cancel-" + branch;
		
		ClientTransaction ctx = response.getCallSession().getClientTransaction(branch);

		if (ctx == null)
		{
			if (LOG.isDebugEnabled())
				LOG.debug("did not find client transaction for response {}", response);
			
			transactionNotFound();
			return;
		}
		
		if (LOG.isDebugEnabled())
            LOG.debug("response {} for transaction {}", response, ctx);
		
		response.setTransaction(ctx);
		ctx.handleResponse(response);
    }
	
	public ClientTransaction sendRequest(SipRequest request, ClientTransactionListener listener) 
    {
		ClientTransaction ctx = new ClientTransaction(request, listener);
		
		if (!request.isAck())
			request.getCallSession().addClientTransaction(ctx);
		
		try 
        {
			ctx.start();
		} 
        catch (IOException e)
        {
			LOG.warn(e);
		}
		return ctx;
	}
	
	public TimersSettings getTimersSettings()
	{
		synchronized (_timersSettings)
		{
			return _timersSettings;
		}
	}

	public void setTimersSettings(TimersSettings timersSettings)
	{
		synchronized (_timersSettings)
		{
			_timersSettings = timersSettings;
		}
	}

	public int getT1() { return getTimersSettings().getT1(); }
	public int getT2() { return getTimersSettings().getT2(); }
	public int getT4() { return getTimersSettings().getT4(); }
	public int getTD() { return getTimersSettings().getTD(); }

	public int getTimerC() { return SipProxy.__timerC; }
	
	public void setT1(int millis)
	{ 
		if (millis < 0)
			throw new IllegalArgumentException("SIP Timers must be positive");
		TimersSettings settings = new TimersSettings(_timersSettings);
		settings.setT1(millis);
		setTimersSettings(settings);
	}
	
	public void setT2(int millis) 
	{
		if (millis < 0)
			throw new IllegalArgumentException("SIP Timers must be positive");
		TimersSettings settings = new TimersSettings(_timersSettings);
		settings.setT2(millis);
		setTimersSettings(settings);
	}
	
	public void setT4(int millis) 
	{
		if (millis < 0)
			throw new IllegalArgumentException("SIP Timers must be positive");
		TimersSettings settings = new TimersSettings(_timersSettings);
		settings.setT4(millis);
		setTimersSettings(settings);
	}
	
	public void setTD(int millis) 
	{
		if (millis < 0)
			throw new IllegalArgumentException("SIP Timers must be positive");
		TimersSettings settings = new TimersSettings(_timersSettings);
		settings.setTD(millis);
		setTimersSettings(settings);
	}
	
	public void setTimerC(int millis) 
	{
		if (millis < 0)
			throw new IllegalArgumentException("SIP Timers must be positive");
		SipProxy.__timerC = millis;
	}
	
	protected void retransReceived() 
	{
		_retransStats.increment();
	}
	
	protected void transactionNotFound()
	{
		_notFoundStats.increment();
	}
	
	public long getRetransmissions()
	{
		return _retransStats.getCurrent();
	}
	
	public long getNotFoundTransactions()
	{
		return _notFoundStats.getCurrent();
	}
	
	public void statsReset()
    {
        _retransStats.reset();
        _notFoundStats.reset();
    }
}
