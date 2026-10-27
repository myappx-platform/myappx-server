/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution                       *
 * Copyright (C) 2010 Heng Sin Low                                            *
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
package org.adempiere.base.event;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.compiere.model.MSysConfig;
import org.compiere.util.Env;
import org.osgi.service.event.Event;

/**
 * Best-effort publisher for {@link IEventTopics#OBSERVATION}.
 * Failures are swallowed so a subscriber cannot break save, callout, document, or UI paths.
 * <p>
 * Payload never includes field values — only table, record id, column names, codes, and short messages.
 * Kinds: {@code save}, {@code callout}, {@code doc_action}, {@code process}, {@code workflow},
 * {@code ui}, {@code tab}, {@code field}.
 */
public final class Observation {

	public static final String SCHEMA = "schema";
	public static final String KIND = "kind";
	public static final String OUTCOME = "outcome";
	public static final String TABLE = "table";
	public static final String RECORD_ID = "recordId";
	public static final String COLUMN = "column";
	public static final String CODE = "code";
	public static final String MESSAGE = "message";
	public static final String CHANGED = "changed";

	public static final String SCHEMA_VERSION = "1";

	private static final int DEFAULT_FIELD_THROTTLE_MS = 500;

	private static final int MESSAGE_LIMIT = 200;
	private static final ConcurrentHashMap<String, AtomicLong> FIELD_LAST_MS = new ConcurrentHashMap<>();

	private Observation() {
	}

	/**
	 * Post one observation. Column values are not included. {@code changed} is a comma-separated
	 * list of column names.
	 *
	 * @param kind save, callout, doc_action, process, workflow, ui, tab, or field
	 * @param outcome ok, blocked, error, open, activate, close, select, …
	 * @param table table name, may be empty
	 * @param recordId record id, or 0
	 * @param column column name, may be empty
	 * @param code AD message, action code, window id, tab id, …
	 * @param message short text; SQL from DBExecuteError is dropped
	 * @param changed column names only
	 */
	public static void publish(String kind, String outcome, String table, int recordId,
			String column, String code, String message, String changed) {
		try {
			IEventManager manager = EventManager.getIfStarted();
			if (manager == null) {
				return;
			}
			String safeCode = code != null ? code : "";
			String safeMessage = message != null ? message : "";
			if ("DBExecuteError".equals(safeCode) || safeMessage.startsWith("DBExecuteError:")) {
				safeCode = "DBExecuteError";
				safeMessage = "";
			}
			Map<String, Object> data = new HashMap<String, Object>();
			data.put(SCHEMA, SCHEMA_VERSION);
			data.put(KIND, kind != null ? kind : "");
			data.put(OUTCOME, outcome != null ? outcome : "");
			data.put(TABLE, table != null ? table : "");
			data.put(RECORD_ID, Integer.toString(recordId));
			data.put(COLUMN, column != null ? column : "");
			data.put(CODE, truncate(safeCode));
			data.put(MESSAGE, truncate(safeMessage));
			data.put(CHANGED, changed != null ? changed : "");
			Event event = EventManager.newEvent(IEventTopics.OBSERVATION, data, true);
			manager.postEvent(event);
		} catch (Throwable ignored) {
			// observation must not affect the business path
		}
	}

	/**
	 * Publish {@code kind=field} when SysConfig {@link MSysConfig#IDEMPIERE_OBSERVATION_FIELD} is Y,
	 * throttled per session+column.
	 */
	public static void publishFieldChange(String table, int recordId, String column) {
		if (column == null || column.isBlank()) {
			return;
		}
		try {
			if (!MSysConfig.getBooleanValue(MSysConfig.IDEMPIERE_OBSERVATION_FIELD, false,
					Env.getAD_Client_ID(Env.getCtx()))) {
				return;
			}
			int throttle = MSysConfig.getIntValue(MSysConfig.IDEMPIERE_OBSERVATION_FIELD_THROTTLE_MS,
					DEFAULT_FIELD_THROTTLE_MS, Env.getAD_Client_ID(Env.getCtx()));
			if (throttle < 0) {
				throttle = DEFAULT_FIELD_THROTTLE_MS;
			}
			int sessionId = Env.getContextAsInt(Env.getCtx(), Env.AD_SESSION_ID);
			String key = sessionId + "|" + (table != null ? table : "") + "|" + column;
			long now = System.currentTimeMillis();
			AtomicLong last = FIELD_LAST_MS.computeIfAbsent(key, k -> new AtomicLong(0));
			long prev = last.get();
			if (now - prev < throttle) {
				return;
			}
			if (!last.compareAndSet(prev, now)) {
				return;
			}
			publish("field", "ok", table != null ? table : "", recordId, column, "", "", column);
		} catch (Throwable ignored) {
			// observation must not affect the business path
		}
	}

	private static String truncate(String value) {
		if (value == null) {
			return "";
		}
		String trimmed = value.replace('\n', ' ').replace('\r', ' ').trim();
		if (trimmed.length() <= MESSAGE_LIMIT) {
			return trimmed;
		}
		return trimmed.substring(0, MESSAGE_LIMIT);
	}
}
