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

import javax.servlet.sip.UAMode;

import org.cipango.server.SipRequest;
import org.cipango.server.SipResponse;
import org.cipango.server.session.Session;
import org.cipango.util.TimerTask;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class ServerTransaction extends Transaction
{	
	private static final Logger LOG = Log.getLogger(ServerTransaction.class);
	
	// INVITE response retransmit interval
	private static final int TIMER_G = 0;
	
	// Wait time for ACK receipt
	private static final int TIMER_H = 1;
	
	// Wait time for ACK retransmits
	private static final int TIMER_I = 2;
	
	// Wait time for non-INVITE request retransmits
	private static final int TIMER_J = 3;
	
	// Wait time for accepted INVITE request retransmits
	private static final int TIMER_L = 4;

	private static final char[] TIMERS = {'G','H','I','J','L'};
    
	private SipResponse _provisionalResponse;
    private SipResponse _finalResponse;
    
    private ServerTransactionListener _listener;
    
    private long gDelay;
    
	public ServerTransaction(SipRequest request) 
    {
		super(request, request.getTopVia().getBranch());
		_timers = new TimerTask[TIMER_L+1];
	    gDelay = _timersConfiguration.getT1();

		setConnection(request.getConnection());
		
		if (isInvite()) 
			setState(STATE_PROCEEDING);
		else 
			setState(STATE_TRYING);
	}
	
    public void setListener(ServerTransactionListener listener)
    {
    	if (_listener == null)
    		_listener = listener;
    }
    
    public void cancel(SipRequest cancel) throws IOException
    {
    	if (_listener == null) {
    		Session session = _request.session();
    		if (session == null) {
    			LOG.warn("No transaction listener set on {}. Could not handle:\n{}", this, cancel);
    			return;
    		}
    		
        	if (session.getUA() == null)
    			session.createUA(UAMode.UAS);
			setListener(session.getUA());
    	}
    	
    	_listener.handleCancel(this, cancel);
    }
    
    public void handleAck(SipRequest ack)
    {
    	if (isInvite())
    	{
    		if (_state != STATE_COMPLETED)
    		{
    			LOG.info("ACK in state {} for transaction {}", getStateAsString(), this);
    			return;
    		}
    		setState(STATE_CONFIRMED);
    		cancelTimer(TIMER_H); cancelTimer(TIMER_G);
    		
    		if (isTransportReliable())
    			terminate(); // TIMER_I == 0
    		else
    			startTimer(TIMER_I, _timersConfiguration.getTI());
    	}
    	else
    	{
    		LOG.info("ACK for non-INVITE: {}", this);
    	}
    }
    
    public void handleRetransmission(SipRequest request)
    {
    	// TODO cseq
    	SipResponse response = null;
    	
    	if (_state == STATE_PROCEEDING)
    		response = _provisionalResponse;
    	else if (_state == STATE_COMPLETED)
    		response = _finalResponse;
    	
    	if (response != null)
    	{
    		try
    		{
    			doSend(response);
    		}
    		catch (Exception e)
    		{
    			LOG.debug(e);
    		}
    	}
    }
	
	public boolean isServer() 
    {
		return true;
	}
	
	public void send(SipResponse response) 
    {
		int status = response.getStatus();
		
		if (isInvite()) 
        {
            switch (_state)
            {
            case STATE_PROCEEDING:
                if (status < 200) 
                {
                    _provisionalResponse = response;
                } 
                else if (status >= 300) 
                {
                    setState(STATE_COMPLETED);
                	_finalResponse = response;

                    if (!isTransportReliable()) 
                        startTimer(TIMER_G, gDelay);
                    
                    startTimer(TIMER_H, _timersConfiguration.getTH());
                } 
                else if (status >= 200) 
                {
                	setState(STATE_ACCEPTED);
                	startTimer(TIMER_L, _timersConfiguration.getTL());
                }
                break;
            case STATE_ACCEPTED:
            	if (!(status >= 200 && status < 300))
            		throw new IllegalStateException("!2xx && Accepted");
            	break;
            default:
                throw new IllegalStateException("sendInvite && !Proceeding");
            }
        }
		else 
        {
            switch (_state)
            {
            case STATE_TRYING:
            case STATE_PROCEEDING:
                if (response.getStatus() < 200) 
                {
                    _provisionalResponse = response;
                    if (_state == STATE_TRYING) 
                        setState(STATE_PROCEEDING);                    
                } 
                else if (response.getStatus() >= 200)
                {
                    setState(STATE_COMPLETED);
                    _finalResponse = response;

                    if (isTransportReliable()) 
                        terminate(); // TIMER_J == 0
                    else 
                    	startTimer(TIMER_J, _timersConfiguration.getTJ());
                } 
                break;
            default:
                throw new IllegalStateException("sendNonInvite && !(state == Trying || state == Proceeding)");
            }
        }
		
		try 
        {
			doSend(response);
        }
		catch (IOException e) 
        {
			LOG.debug(e);
		}
	}
	
	private void doSend(SipResponse response) throws IOException 
    {
		getServer().getConnectorManager().sendResponse(response, getConnection());
	}
	
	public void timeout(int id) 
    {
		switch(id) 
        {
		case TIMER_G:
			try 
            {
				doSend(_finalResponse);
			} 
            catch (IOException e) 
            {
				LOG.debug("failed to retransmit response on timer G expiry", e);
			}
			gDelay = gDelay * 2;
			startTimer(TIMER_G, Math.min(gDelay, _timersConfiguration.getT2()));
			break;
		case TIMER_H:
			// TODO ? SipErrorListener.noAck 
			cancelTimer(TIMER_G);
			terminate();
			break;
		case TIMER_I:
		case TIMER_J:
		case TIMER_L:
			terminate();
			break;
		default:
			throw new RuntimeException("unknown timeout id " + id);
		}
	}
	
	public void terminate()
    {
		_provisionalResponse = _finalResponse = null;
        setState(STATE_TERMINATED);
        getCallSession().removeServerTransaction(this);
        
        if (_listener != null) // TODO check
        	_listener.transactionTerminated(this);
    }
	
	public String asString(int timer)
	{
		return "Timer" + TIMERS[timer];
	}
}
