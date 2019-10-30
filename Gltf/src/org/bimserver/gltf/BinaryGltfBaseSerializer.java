package org.bimserver.gltf;

/******************************************************************************
 * Copyright (C) 2009-2019  BIMserver.org
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see {@literal<http://www.gnu.org/licenses/>}.
 *****************************************************************************/

import org.bimserver.models.geometry.GeometryData;
import org.bimserver.models.geometry.GeometryInfo;
import org.bimserver.models.ifc2x3tc1.IfcAnnotation;
import org.bimserver.models.ifc2x3tc1.IfcProduct;
import org.bimserver.plugins.serializers.EmfSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BinaryGltfBaseSerializer extends EmfSerializer  {
	private static final Logger LOGGER = LoggerFactory.getLogger(BinaryGltfSerializer2.class);

	protected boolean checkGeometry(IfcProduct ifcProduct, boolean print) {
		String name = ifcProduct.eClass().getName();
		if (name.equals("IfcOpeningElement") || name.equals("IfcBuildingStorey") || name.equals("IfcBuilding")) {
			return false;
		}
		GeometryInfo geometryInfo = ifcProduct.getGeometry();
		if (geometryInfo == null) {
			if (ifcProduct instanceof IfcAnnotation) {
				return false;
			}
			if (print) {
				LOGGER.info("No GeometryInfo for " + name);
			}
			return false;
		}
		GeometryData geometryData = geometryInfo.getData();
		if (geometryData == null) {
			if (print) {
				LOGGER.info("No GeometryData for " + name);
			}
			return false;
		}
		if (geometryData.getVertices() == null) {
			if (print) {
				LOGGER.info("No Vertices for " + name);
			}
			return false;
		}
		return true;
	}
}