/***********************************************************************
 * This file is part of iDempiere ERP Open Source                      *
 * http://www.idempiere.org                                            *
 *                                                                     *
 * Copyright (C) Contributors                                          *
 *                                                                     *
 * This program is free software; you can redistribute it and/or       *
 * modify it under the terms of the GNU General Public License         *
 * as published by the Free Software Foundation; either version 2      *
 * of the License, or (at your option) any later version.              *
 *                                                                     *
 * This program is distributed in the hope that it will be useful,     *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of      *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the        *
 * GNU General Public License for more details.                        *
 *                                                                     *
 * You should have received a copy of the GNU General Public License   *
 * along with this program; if not, write to the Free Software         *
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,          *
 * MA 02110-1301, USA.                                                 *
 *                                                                     *
 * Contributors:                                                       *
 * - Ken Longnan                                                        *
 **********************************************************************/
package org.adempiere.base.callout;

import java.util.Properties;

import org.adempiere.base.IColumnCallout;
import org.adempiere.base.annotation.Callout;
import org.adempiere.model.GridTabWrapper;
import org.compiere.model.GridField;
import org.compiere.model.GridTab;
import org.compiere.model.I_AD_UserDef_Tab;
import org.compiere.model.MTab;
import org.compiere.model.X_AD_UserDef_Tab;
import org.compiere.util.Env;

/**
 * Tab Customization callouts for IsInsertRecord / IsReadOnly validation.
 * Registered via {@code @Callout}; no AD_Column.Callout configuration.
 * Save-path checks stay in {@link org.compiere.model.MUserDefTab#beforeSave(boolean)}.
 */
@Callout(tableName = I_AD_UserDef_Tab.Table_Name, columnName = I_AD_UserDef_Tab.COLUMNNAME_IsInsertRecord)
@Callout(tableName = I_AD_UserDef_Tab.Table_Name, columnName = I_AD_UserDef_Tab.COLUMNNAME_IsReadOnly)
public class CalloutUserDefTab implements IColumnCallout {

	@Override
	public String start(Properties ctx, int WindowNo, GridTab mTab, GridField mField,
			Object value, Object oldValue) {

		if (I_AD_UserDef_Tab.COLUMNNAME_IsInsertRecord.equals(mField.getColumnName()))
			return isInsertRecord(ctx, WindowNo, mTab, value);

		if (I_AD_UserDef_Tab.COLUMNNAME_IsReadOnly.equals(mField.getColumnName()))
			return isReadOnly(ctx, WindowNo, mTab, value);

		return null;
	}

	/**
	 * Validate IsInsertRecord against base tab configuration.
	 */
	private String isInsertRecord(Properties ctx, int WindowNo, GridTab mTab, Object value) {
		I_AD_UserDef_Tab udTab = GridTabWrapper.create(mTab, I_AD_UserDef_Tab.class);
		if (udTab == null || udTab.getAD_Tab_ID() <= 0)
			return null;

		String isInsertRecordValue = (String) value;
		if (!X_AD_UserDef_Tab.ISINSERTRECORD_Yes.equals(isInsertRecordValue))
			return null;

		MTab baseTab = new MTab(Env.getCtx(), udTab.getAD_Tab_ID(), null);
		boolean baseTabIsInsertRecord = baseTab.isInsertRecord();
		boolean baseTabIsReadOnly = baseTab.isReadOnly();

		if (!baseTabIsInsertRecord || baseTabIsReadOnly) {
			udTab.setIsInsertRecord(null);
			mTab.setValue(I_AD_UserDef_Tab.COLUMNNAME_IsInsertRecord, null);

			if (!baseTabIsInsertRecord)
				return "Base tab does not allow Insert Record. User Define Tab Customization cannot override this setting.";

			return "Base tab is Read Only. User Define Tab Customization cannot enable Insert Record when base tab is Read Only.";
		}

		return null;
	}

	/**
	 * Sync IsReadOnly / IsInsertRecord with base tab configuration.
	 */
	private String isReadOnly(Properties ctx, int WindowNo, GridTab mTab, Object value) {
		I_AD_UserDef_Tab udTab = GridTabWrapper.create(mTab, I_AD_UserDef_Tab.class);
		if (udTab == null)
			return null;

		MTab baseTab = new MTab(Env.getCtx(), udTab.getAD_Tab_ID(), null);
		boolean baseTabIsReadOnly = baseTab.isReadOnly();
		String isReadOnlyValue = (String) value;

		if (X_AD_UserDef_Tab.ISREADONLY_Yes.equals(isReadOnlyValue)) {
			udTab.setIsInsertRecord(null);
			mTab.setValue(I_AD_UserDef_Tab.COLUMNNAME_IsInsertRecord, null);
			return null;
		}

		if (X_AD_UserDef_Tab.ISREADONLY_No.equals(isReadOnlyValue) && baseTabIsReadOnly) {
			udTab.setIsReadOnly(null);
			mTab.setValue(X_AD_UserDef_Tab.COLUMNNAME_IsReadOnly, null);
			udTab.setIsInsertRecord(null);
			mTab.setValue(I_AD_UserDef_Tab.COLUMNNAME_IsInsertRecord, null);
			return "Base tab is Read Only. User Define Tab Customization cannot override this setting.";
		}

		return null;
	}
}
