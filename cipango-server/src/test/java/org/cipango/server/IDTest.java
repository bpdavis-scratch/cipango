package org.cipango.server;

import static junit.framework.Assert.assertEquals;

import org.junit.Test;

public class IDTest
{
	@Test
	public void testCallId()
	{
		String[] cids = 
		{ 
		  "f81d4fae-7dec-11d0-a765-00a0c91e6bf6@foo.bar.com",
		  "*%@localhost"
		};
		
		for (int i = 0; i < cids.length; i++)
		{	
			String callId = ID.newCallId(cids[i]);
			String callId2 = ID.newCallId(callId);
			
			assertEquals(cids[i], ID.getCallSessionId(callId));
			assertEquals(cids[i], ID.getCallSessionId(callId2));
		}
	}
}
