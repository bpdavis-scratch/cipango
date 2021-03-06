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

package org.cipango.dns.util;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;

public abstract class BufferUtil 
{
	public static int getInt(Buffer buffer)
	{ 
		return (buffer.get() & 0xff) << 24 
			| (buffer.get() & 0xff) << 16 
			| (buffer.get() & 0xff) << 8
			| (buffer.get() & 0xff);
	}
	
	public static Buffer putInt(Buffer buffer, int value)
	{ 
		buffer.put((byte)  (value >> 24 & 0xff));
		buffer.put((byte)  (value >> 16 & 0xff));
		buffer.put((byte)  (value >> 8 & 0xff));
		buffer.put((byte)  (value & 0xff));
		return buffer;
	}	
	
	public static Buffer pokeInt(Buffer buffer, int index, int value)
	{
		buffer.poke(index, (byte)  (value >> 24 & 0xff));
		buffer.poke(index+1, (byte)  (value >> 16 & 0xff));
		buffer.poke(index+2, (byte)  (value >> 8 & 0xff));
		buffer.poke(index+3, (byte)  (value & 0xff));
		return buffer;
	}
	
	public static Buffer poke16(Buffer buffer, int index, int val)
	{
		buffer.poke(index, (byte) ((val >> 8) & 0xFF));
		buffer.poke(index + 1, (byte) (val & 0xFF));
		return buffer;
	}
	
	public static Buffer put16(Buffer buffer, int val)
	{
		buffer.put((byte) ((val >> 8) & 0xFF));
		buffer.put((byte) (val & 0xFF));
		return buffer;
	}
	
	public static int get16(Buffer buffer)
	{
		return (buffer.get() & 0xff) << 8 | (buffer.get() & 0xff);
	}
	
	public static Buffer ensureSpace(Buffer buffer, int space)
	{
		if (buffer.space() < space)
		{
			ByteArrayBuffer largerBuffer = new ByteArrayBuffer(buffer.capacity() + 100);
			largerBuffer.put(buffer);
			return largerBuffer;
		}
		else
			return buffer;
	}
}
