/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution                       *
 * Copyright (C) 1999-2006 ComPiere, Inc. All Rights Reserved.                *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program; if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 *****************************************************************************/
package org.compiere.util;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Jetty-compatible OBF obfuscation (same as {@code org.eclipse.jetty.util.security.Password}).
 */
public final class ObfuscateUtil {

	public static final String OBFUSCATE_PREFIX = "OBF:";

	private ObfuscateUtil() {
	}

	public static String deobfuscateIfNeeded(String value) {
		if (value != null && value.startsWith(OBFUSCATE_PREFIX))
			return deobfuscate(value);
		return value;
	}

	public static String obfuscate(String s) {
		StringBuilder buf = new StringBuilder();
		byte[] b = s.getBytes(StandardCharsets.UTF_8);
		buf.append(OBFUSCATE_PREFIX);
		for (int i = 0; i < b.length; i++) {
			byte b1 = b[i];
			byte b2 = b[b.length - (i + 1)];
			if (b1 < 0 || b2 < 0) {
				int i0 = (0xff & b1) * 256 + (0xff & b2);
				String x = Integer.toString(i0, 36).toLowerCase(Locale.ENGLISH);
				buf.append("U0000", 0, 5 - x.length());
				buf.append(x);
			} else {
				int i1 = 127 + b1 + b2;
				int i2 = 127 + b1 - b2;
				int i0 = i1 * 256 + i2;
				String x = Integer.toString(i0, 36).toLowerCase(Locale.ENGLISH);
				buf.append("000", 0, 4 - x.length());
				buf.append(x);
			}
		}
		return buf.toString();
	}

	public static String deobfuscate(String s) {
		if (s.startsWith(OBFUSCATE_PREFIX))
			s = s.substring(OBFUSCATE_PREFIX.length());

		byte[] b = new byte[s.length() / 2];
		int l = 0;
		for (int i = 0; i < s.length(); i += 4) {
			if (s.charAt(i) == 'U') {
				i++;
				String x = s.substring(i, i + 4);
				int i0 = Integer.parseInt(x, 36);
				byte bx = (byte) (i0 >> 8);
				b[l++] = bx;
			} else {
				String x = s.substring(i, i + 4);
				int i0 = Integer.parseInt(x, 36);
				int i1 = (i0 / 256);
				int i2 = (i0 % 256);
				byte bx = (byte) ((i1 + i2 - 254) / 2);
				b[l++] = bx;
			}
		}
		return new String(b, 0, l, StandardCharsets.UTF_8);
	}
}
