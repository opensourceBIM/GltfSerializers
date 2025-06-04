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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.Set;

import org.bimserver.geometry.IfcColors;
import org.bimserver.geometry.Matrix;
import org.bimserver.models.geometry.GeometryData;
import org.bimserver.models.geometry.GeometryInfo;
import org.bimserver.models.ifc2x3tc1.IfcProduct;
import org.bimserver.plugins.serializers.ProgressReporter;
import org.bimserver.plugins.serializers.SerializerException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Charsets;
import com.google.common.io.LittleEndianDataOutputStream;

/**
 * @author Ruben de Laat
 *
 *         Annoying things about glTF so far: - Total and scene length have to
 *         be computed in advance, so no streaming is possible
 *
 */
public class BinaryGltfSerializer extends BinaryGltfBaseSerializer {

	private static final String VERTEX_COLOR_MATERIAL = "VertexColorMaterial";
	private static final int FLOAT_VEC_4 = 35666;
//	private static final int SHORT = 5122;
	private static final int ARRAY_BUFFER = 34962;
	private static final int ELEMENT_ARRAY_BUFFER = 34963;
	private static final String MAGIC = "glTF";
	private static final int UNSIGNED_SHORT = 5123;
	private static final int TRIANGLES = 4;
	private static final int FLOAT = 5126;
	private int JSON_SCENE_FORMAT = 0;
	private int FORMAT_VERSION_1 = 1;

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private ObjectNode buffers;
	private ByteBuffer body;
	private ObjectNode meshes;
	private ObjectNode accessors;
	private int accessorCounter = 0;
	private int bufferViewCounter;
	private ObjectNode buffersViews;
	private ArrayNode defaultSceneNodes;
	private ObjectNode scenesNode;
	private ObjectNode gltfNode;
	private ObjectNode nodes;
	
	private byte[] vertexColorFragmentShaderBytes;
	private byte[] vertexColorVertexShaderBytes;
	private byte[] materialColorFragmentShaderBytes;
	private byte[] materialColorVertexShaderBytes;
	
	float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
	float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
	private ArrayNode modelTranslation;
	private ObjectNode materials;
	private ObjectNode shaders;
	
	private final Set<String> createdMaterials = new HashSet<>();
	private ArrayNode translationChildrenNode;

	public BinaryGltfSerializer(byte[] vertexColorFragmentShaderBytes, byte[] vertexColorVertexShaderBytes, byte[] materialColorFragmentShaderBytes, byte[] materialColorVertexShaderBytes) {
		this.vertexColorFragmentShaderBytes = vertexColorFragmentShaderBytes;
		this.vertexColorVertexShaderBytes = vertexColorVertexShaderBytes;
		this.materialColorFragmentShaderBytes = materialColorFragmentShaderBytes;
		this.materialColorVertexShaderBytes = materialColorVertexShaderBytes;
	}

	@Override
	protected boolean write(OutputStream outputStream, ProgressReporter progressReporter) throws SerializerException {
		gltfNode = OBJECT_MAPPER.createObjectNode();

		buffers = OBJECT_MAPPER.createObjectNode();
		meshes = OBJECT_MAPPER.createObjectNode();
		buffersViews = OBJECT_MAPPER.createObjectNode();
		scenesNode = OBJECT_MAPPER.createObjectNode();
		accessors = OBJECT_MAPPER.createObjectNode();
		nodes = OBJECT_MAPPER.createObjectNode();
		materials = OBJECT_MAPPER.createObjectNode();
		shaders = OBJECT_MAPPER.createObjectNode();
		
		gltfNode.set("meshes", meshes);
		gltfNode.set("bufferViews", buffersViews);
		gltfNode.set("scenes", scenesNode);
		gltfNode.set("accessors", accessors);
		gltfNode.set("nodes", nodes);
		gltfNode.set("buffers", buffers);
		gltfNode.set("materials", materials);
		gltfNode.set("shaders", shaders);
		
		createVertexColorMaterial();

		try {
			LittleEndianDataOutputStream dataOutputStream = new LittleEndianDataOutputStream(outputStream);

			generateSceneAndBody();

//			StringWriter stringWriter = new StringWriter();
//			OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(stringWriter, gltfNode);
//			System.out.println(stringWriter);

			byte[] sceneBytes = gltfNode.toString().getBytes(Charsets.UTF_8);
			writeHeader(dataOutputStream, 20, sceneBytes.length, body.capacity());
			writeScene(dataOutputStream, sceneBytes);
			writeBody(dataOutputStream, body.array());
		} catch (Exception e) {
			throw new SerializerException(e);
		}
		return false;
	}

	private void createModelNode() {
		ObjectNode translationNode = OBJECT_MAPPER.createObjectNode();
		modelTranslation = OBJECT_MAPPER.createArrayNode();
		
		translationChildrenNode = OBJECT_MAPPER.createArrayNode();
		translationNode.set("children", translationChildrenNode);
		translationNode.set("translation", modelTranslation);

		ArrayNode scaleNode = OBJECT_MAPPER.createArrayNode();
		float scale = (float) (getProjectInfo().getMultiplierToMm() / 1000.f);
		scaleNode.add(scale);
		scaleNode.add(scale);
		scaleNode.add(scale);
		translationNode.set("scale", scaleNode);

		ObjectNode rotationNode = OBJECT_MAPPER.createObjectNode();
		ArrayNode rotation = OBJECT_MAPPER.createArrayNode();
		ArrayNode rotationChildrenNode = OBJECT_MAPPER.createArrayNode();
		rotationChildrenNode.add("translationNode");
		rotationNode.set("children", rotationChildrenNode);
		rotationNode.set("rotation", rotation);
		
		rotation.add(1f);
		rotation.add(0f);
		rotation.add(0f);
		rotation.add(-1f);
		
		nodes.set("rotationNode", rotationNode);
		nodes.set("translationNode", translationNode);
		defaultSceneNodes.add("rotationNode");
	}

	private void generateSceneAndBody() throws SerializerException {
		int totalBodyByteLength = 0;
		int totalIndicesByteLength = 0;
		int totalVerticesByteLength = 0;
		int totalNormalsByteLength = 0;
		int totalColorsByteLength = 0;

		int maxIndexValues = 16389;

		for (IfcProduct ifcProduct : model.getAllWithSubTypes(IfcProduct.class)) {
			if (checkGeometry(ifcProduct, true)) {
				GeometryInfo geometryInfo = ifcProduct.getGeometry();
				GeometryData data = geometryInfo.getData();
				int nrIndicesBytes = data.getIndices().getData().length;

				totalIndicesByteLength += nrIndicesBytes / 2;
				if (nrIndicesBytes > 4 * maxIndexValues) {
					int nrIndices = nrIndicesBytes / 4;
					totalVerticesByteLength += nrIndices * 3 * 4;				
					totalNormalsByteLength += nrIndices * 3 * 4;				
					if (data.getColorsQuantized() != null) {
						totalColorsByteLength += nrIndices * 4 * 4;
					}
				} else {
					totalVerticesByteLength += data.getVertices().getData().length;
					totalNormalsByteLength += data.getNormals().getData().length;
					if (data.getColorsQuantized() != null) {
						totalColorsByteLength += data.getColorsQuantized().getData().length;
					}
				}
			}
		}
		totalBodyByteLength = totalIndicesByteLength + totalVerticesByteLength + totalNormalsByteLength + totalColorsByteLength;

		body = ByteBuffer.allocate(totalBodyByteLength + materialColorFragmentShaderBytes.length + materialColorVertexShaderBytes.length + vertexColorFragmentShaderBytes.length + vertexColorVertexShaderBytes.length);
		body.order(ByteOrder.LITTLE_ENDIAN);
		
		ByteBuffer newIndicesBuffer = ByteBuffer.allocate(totalIndicesByteLength);
		newIndicesBuffer.order(ByteOrder.LITTLE_ENDIAN);
		
		ByteBuffer newVerticesBuffer = ByteBuffer.allocate(totalVerticesByteLength);
		newVerticesBuffer.order(ByteOrder.LITTLE_ENDIAN);
		
		ByteBuffer newNormalsBuffer = ByteBuffer.allocate(totalNormalsByteLength);
		newNormalsBuffer.order(ByteOrder.LITTLE_ENDIAN);
		
		ByteBuffer newColorsBuffer = ByteBuffer.allocate(totalColorsByteLength);
		newColorsBuffer.order(ByteOrder.LITTLE_ENDIAN);

		String indicesBufferView = createBufferView(totalIndicesByteLength, 0, ELEMENT_ARRAY_BUFFER);
		String verticesBufferView = createBufferView(totalVerticesByteLength, totalIndicesByteLength, ARRAY_BUFFER);
		String normalsBufferView = createBufferView(totalNormalsByteLength, totalIndicesByteLength + totalVerticesByteLength, ARRAY_BUFFER);
		String colorsBufferView = null;
		
		scenesNode.set("defaultScene", createDefaultScene());
		createModelNode();

		for (IfcProduct ifcProduct : model.getAllWithSubTypes(IfcProduct.class)) {
			if (checkGeometry(ifcProduct, false)) {
				GeometryInfo geometryInfo = ifcProduct.getGeometry();
				ByteBuffer matrixByteBuffer = ByteBuffer.wrap(ifcProduct.getGeometry().getTransformation());
				matrixByteBuffer.order(ByteOrder.LITTLE_ENDIAN);
				DoubleBuffer doubleBuffer = matrixByteBuffer.asDoubleBuffer();
				float[] matrix = new float[16];
				for (int i=0; i<doubleBuffer.capacity(); i++) {
					matrix[i] = (float) doubleBuffer.get();
				}

				updateExtends(geometryInfo, matrix);
			}
		}
		
		float[] offsets = getOffsets();
		
		// This will "normalize" the model by moving it's axis-aligned bounding box center to the 0-point. This will always be the wrong position, but at least the building will be close to the 0-point
		modelTranslation.add(-offsets[0]);
		modelTranslation.add(-offsets[1]);
		modelTranslation.add(-offsets[2]);

		for (IfcProduct ifcProduct : model.getAllWithSubTypes(IfcProduct.class)) {
			if (checkGeometry(ifcProduct, false)) {
				GeometryInfo geometryInfo = ifcProduct.getGeometry();
				int startPositionIndices = newIndicesBuffer.position();
				int startPositionVertices = newVerticesBuffer.position();
				int startPositionNormals = newNormalsBuffer.position();
				int startPositionColors = newColorsBuffer.position();
				
				GeometryData data = geometryInfo.getData();
				
				ByteBuffer indicesBuffer = ByteBuffer.wrap(data.getIndices().getData());
				indicesBuffer.order(ByteOrder.LITTLE_ENDIAN);
				IntBuffer indicesIntBuffer = indicesBuffer.asIntBuffer();
				
				ByteBuffer verticesBuffer = ByteBuffer.wrap(data.getVertices().getData());
				verticesBuffer.order(ByteOrder.LITTLE_ENDIAN);
				FloatBuffer verticesFloatBuffer = verticesBuffer.asFloatBuffer();
				
				ByteBuffer normalsBuffer = ByteBuffer.wrap(data.getNormals().getData());
				normalsBuffer.order(ByteOrder.LITTLE_ENDIAN);
				FloatBuffer normalsFloatBuffer = normalsBuffer.asFloatBuffer();

				FloatBuffer materialsFloatBuffer = null;
				if (data.getColorsQuantized() != null) {
					ByteBuffer materialsBuffer = ByteBuffer.wrap(data.getColorsQuantized().getData());
					materialsBuffer.order(ByteOrder.LITTLE_ENDIAN);
					materialsFloatBuffer = materialsBuffer.asFloatBuffer();
				}
				
				if (data.getNrIndices() > maxIndexValues) {
					int totalNrIndices = indicesIntBuffer.capacity();
					int nrParts = (totalNrIndices + maxIndexValues - 1) / maxIndexValues;
					
					ArrayNode primitivesNode = OBJECT_MAPPER.createArrayNode();
					
					for (int part=0; part<nrParts; part++) {
						startPositionIndices = newIndicesBuffer.position();
						startPositionVertices = newVerticesBuffer.position();
						startPositionNormals = newNormalsBuffer.position();
						startPositionColors = newColorsBuffer.position();

						short indexCounter = 0;
						int upto = Math.min((part + 1) * maxIndexValues, totalNrIndices);
						for (int i=part * maxIndexValues; i<upto; i++) {
							newIndicesBuffer.putShort(indexCounter++);
						}
						
						int nrVertices = upto - part * maxIndexValues;
						
						for (int i=part * maxIndexValues; i<upto; i+=3) {
							int oldIndex1 = indicesIntBuffer.get(i);
							int oldIndex2 = indicesIntBuffer.get(i+1);
							int oldIndex3 = indicesIntBuffer.get(i+2);
							
							newVerticesBuffer.putFloat(verticesFloatBuffer.get(oldIndex1 * 3));
							newVerticesBuffer.putFloat(verticesFloatBuffer.get(oldIndex1 * 3 + 1));
							newVerticesBuffer.putFloat(verticesFloatBuffer.get(oldIndex1 * 3 + 2));
							newVerticesBuffer.putFloat(verticesFloatBuffer.get(oldIndex2 * 3));
							newVerticesBuffer.putFloat(verticesFloatBuffer.get(oldIndex2 * 3 + 1));
							newVerticesBuffer.putFloat(verticesFloatBuffer.get(oldIndex2 * 3 + 2));
							newVerticesBuffer.putFloat(verticesFloatBuffer.get(oldIndex3 * 3));
							newVerticesBuffer.putFloat(verticesFloatBuffer.get(oldIndex3 * 3 + 1));
							newVerticesBuffer.putFloat(verticesFloatBuffer.get(oldIndex3 * 3 + 2));
						}
						for (int i=part * maxIndexValues; i<upto; i+=3) {
							int oldIndex1 = indicesIntBuffer.get(i);
							int oldIndex2 = indicesIntBuffer.get(i+1);
							int oldIndex3 = indicesIntBuffer.get(i+2);
							
							newNormalsBuffer.putFloat(normalsFloatBuffer.get(oldIndex1 * 3));
							newNormalsBuffer.putFloat(normalsFloatBuffer.get(oldIndex1 * 3 + 1));
							newNormalsBuffer.putFloat(normalsFloatBuffer.get(oldIndex1 * 3 + 2));
							newNormalsBuffer.putFloat(normalsFloatBuffer.get(oldIndex2 * 3));
							newNormalsBuffer.putFloat(normalsFloatBuffer.get(oldIndex2 * 3 + 1));
							newNormalsBuffer.putFloat(normalsFloatBuffer.get(oldIndex2 * 3 + 2));
							newNormalsBuffer.putFloat(normalsFloatBuffer.get(oldIndex3 * 3));
							newNormalsBuffer.putFloat(normalsFloatBuffer.get(oldIndex3 * 3 + 1));
							newNormalsBuffer.putFloat(normalsFloatBuffer.get(oldIndex3 * 3 + 2));
						}
						if (materialsFloatBuffer != null) {
							for (int i=part * maxIndexValues; i<upto; i+=3) {
								int oldIndex1 = indicesIntBuffer.get(i);
								int oldIndex2 = indicesIntBuffer.get(i+1);
								int oldIndex3 = indicesIntBuffer.get(i+2);
								
								newColorsBuffer.putFloat(materialsFloatBuffer.get(oldIndex1 * 4));
								newColorsBuffer.putFloat(materialsFloatBuffer.get(oldIndex1 * 4 + 1));
								newColorsBuffer.putFloat(materialsFloatBuffer.get(oldIndex1 * 4 + 2));
								newColorsBuffer.putFloat(materialsFloatBuffer.get(oldIndex1 * 4 + 3));
								newColorsBuffer.putFloat(materialsFloatBuffer.get(oldIndex2 * 4));
								newColorsBuffer.putFloat(materialsFloatBuffer.get(oldIndex2 * 4 + 1));
								newColorsBuffer.putFloat(materialsFloatBuffer.get(oldIndex2 * 4 + 2));
								newColorsBuffer.putFloat(materialsFloatBuffer.get(oldIndex2 * 4 + 3));
								newColorsBuffer.putFloat(materialsFloatBuffer.get(oldIndex3 * 4));
								newColorsBuffer.putFloat(materialsFloatBuffer.get(oldIndex3 * 4 + 1));
								newColorsBuffer.putFloat(materialsFloatBuffer.get(oldIndex3 * 4 + 2));
								newColorsBuffer.putFloat(materialsFloatBuffer.get(oldIndex3 * 4 + 3));
							}
						}
						
						ObjectNode primitiveNode = OBJECT_MAPPER.createObjectNode();
						
						String indicesAccessor = addIndicesAccessor(ifcProduct, indicesBufferView, startPositionIndices, nrVertices / 3);
						String verticesAccessor = addVerticesAccessor(ifcProduct, verticesBufferView, startPositionVertices, nrVertices);
						String normalsAccessor = addNormalsAccessor(ifcProduct, normalsBufferView, startPositionNormals, nrVertices);
						String colorAccessor = null;
						if (data.getColor() != null) {
							if (colorsBufferView == null) {
								colorsBufferView = createBufferView(totalColorsByteLength, totalIndicesByteLength + totalVerticesByteLength + totalNormalsByteLength, ARRAY_BUFFER);
							}
							colorAccessor = addColorsAccessor(ifcProduct, colorsBufferView, startPositionColors, nrVertices * 4 / 3);
						}
						primitivesNode.add(primitiveNode);
						
						primitiveNode.put("indices", indicesAccessor);
						primitiveNode.put("mode", TRIANGLES);
						ObjectNode attributesNode = OBJECT_MAPPER.createObjectNode();
						primitiveNode.set("attributes", attributesNode);
						attributesNode.put("NORMAL", normalsAccessor);
						attributesNode.put("POSITION", verticesAccessor);
						if (colorAccessor != null) {
							attributesNode.put("COLOR", colorAccessor);
							primitiveNode.put("material", VERTEX_COLOR_MATERIAL);
						} else {
							primitiveNode.put("material", createOrGetMaterial(ifcProduct.eClass().getName(), IfcColors.getDefaultColor(ifcProduct.eClass().getName())));
						}
					}
					
					String meshName = addMesh(ifcProduct, primitivesNode);
					String nodeName = addNode(meshName, ifcProduct);
					translationChildrenNode.add(nodeName);
				} else {
					for (int i=0; i<indicesIntBuffer.capacity(); i++) {
						int index = indicesIntBuffer.get(i);
						if (index > Short.MAX_VALUE) {
							throw new SerializerException("Index too large to store as short " + index);
						}
						newIndicesBuffer.putShort((short)(index));
					}
					
					newVerticesBuffer.put(data.getVertices().getData());
					newNormalsBuffer.put(data.getNormals().getData());
					if (data.getColorsQuantized() != null) {
						newColorsBuffer.put(data.getColorsQuantized().getData());
					}
					
					int totalNrIndices = indicesIntBuffer.capacity();
					
					ArrayNode primitivesNode = OBJECT_MAPPER.createArrayNode();
					
					ObjectNode primitiveNode = OBJECT_MAPPER.createObjectNode();
					
					String indicesAccessor = addIndicesAccessor(ifcProduct, indicesBufferView, startPositionIndices, totalNrIndices);
					String verticesAccessor = addVerticesAccessor(ifcProduct, verticesBufferView, startPositionVertices, data.getNrVertices());
					String normalsAccessor = addNormalsAccessor(ifcProduct, normalsBufferView, startPositionNormals, data.getNrNormals());
					String colorAccessor = null;
					if (data.getColor() != null) {
						if (colorsBufferView == null) {
							colorsBufferView = createBufferView(totalColorsByteLength, totalIndicesByteLength + totalVerticesByteLength + totalNormalsByteLength, ARRAY_BUFFER);
						}
						colorAccessor = addColorsAccessor(ifcProduct, colorsBufferView, startPositionColors, data.getNrVertices());
					}
					primitivesNode.add(primitiveNode);
					
					primitiveNode.put("indices", indicesAccessor);
					primitiveNode.put("mode", TRIANGLES);
					ObjectNode attributesNode = OBJECT_MAPPER.createObjectNode();
					primitiveNode.set("attributes", attributesNode);
					attributesNode.put("NORMAL", normalsAccessor);
					attributesNode.put("POSITION", verticesAccessor);
					if (colorAccessor != null) {
						attributesNode.put("COLOR", colorAccessor);
						primitiveNode.put("material", VERTEX_COLOR_MATERIAL);
					} else {
						primitiveNode.put("material", createOrGetMaterial(ifcProduct.eClass().getName(), IfcColors.getDefaultColor(ifcProduct.eClass().getName())));
					}
					
					String meshName = addMesh(ifcProduct, primitivesNode);
					String nodeName = addNode(meshName, ifcProduct);
					translationChildrenNode.add(nodeName);
				}
			}
		}
		
		if (newIndicesBuffer.position() != newIndicesBuffer.capacity()) {
			throw new SerializerException("Not all space used");
		}
		if (newVerticesBuffer.position() != newVerticesBuffer.capacity()) {
			throw new SerializerException("Not all space used");
		}
		if (newNormalsBuffer.position() != newNormalsBuffer.capacity()) {
			throw new SerializerException("Not all space used");
		}
		if (newColorsBuffer.position() != newColorsBuffer.capacity()) {
			throw new SerializerException("Not all space used");
		}
		
		newIndicesBuffer.position(0);
		newVerticesBuffer.position(0);
		newNormalsBuffer.position(0);
		newColorsBuffer.position(0);
		
		body.put(newIndicesBuffer);
		body.put(newVerticesBuffer);
		body.put(newNormalsBuffer);
		body.put(newColorsBuffer);

		String vertexColorFragmentShaderBufferViewName = createBufferView(vertexColorFragmentShaderBytes.length, body.position());
		body.put(vertexColorFragmentShaderBytes);

		String vertexColorVertexShaderBufferViewName = createBufferView(vertexColorVertexShaderBytes.length, body.position());
		body.put(vertexColorVertexShaderBytes);

		String materialColorFragmentShaderBufferViewName = createBufferView(materialColorFragmentShaderBytes.length, body.position());
		body.put(materialColorFragmentShaderBytes);
		
		String materialColorVertexShaderBufferViewName = createBufferView(materialColorVertexShaderBytes.length, body.position());
		body.put(materialColorVertexShaderBytes);
		
		gltfNode.set("animations", createAnimations());
		gltfNode.set("asset", createAsset());
		gltfNode.set("programs", createPrograms());
		gltfNode.put("scene", "defaultScene");
		gltfNode.set("skins", createSkins());
		gltfNode.set("techniques", createTechniques());

		createVertexColorShaders(vertexColorFragmentShaderBufferViewName, vertexColorVertexShaderBufferViewName);
		createMaterialColorShaders(materialColorFragmentShaderBufferViewName, materialColorVertexShaderBufferViewName);
		
		addBuffer("binary_glTF", "arraybuffer", body.capacity());

		ArrayNode extensions = OBJECT_MAPPER.createArrayNode();
		extensions.add("KHR_binary_glTF");
		gltfNode.set("extensionsUsed", extensions);
	}

	private float[] getOffsets() {
		float[] changes = new float[3];
		for (int i=0; i<3; i++) {
			changes[i] = (max[i] - min[i]) / 2.0f + min[i];
			changes[i] *= (float) (getProjectInfo().getMultiplierToMm() / 1000.0f);
		}
		return changes;
	}
	
	private void updateExtends(GeometryInfo geometryInfo, float[] matrix) {
		ByteBuffer verticesByteBufferBuffer = ByteBuffer.wrap(geometryInfo.getData().getVertices().getData());
		verticesByteBufferBuffer.order(ByteOrder.LITTLE_ENDIAN);
		FloatBuffer floatBuffer = verticesByteBufferBuffer.asFloatBuffer();
		for (int i=0; i<floatBuffer.capacity(); i+=3) {
			float[] input = new float[]{floatBuffer.get(i), floatBuffer.get(i + 1), floatBuffer.get(i + 2), 1};
			float[] output = new float[4];
			Matrix.multiplyMV(output, 0, matrix, 0, input, 0);
			for (int j=0; j<3; j++) {
				float value = output[j];
				if (value > max[j]) {
					max[j] = value;
				}
				if (value < min[j]) {
					min[j] = value;
				}
			}
		}
	}

	private String addNode(String meshName, IfcProduct ifcProduct) {
		String nodeName = "node_" + ifcProduct.getOid();
		ObjectNode nodeNode = OBJECT_MAPPER.createObjectNode();

		ArrayNode matrixArray = OBJECT_MAPPER.createArrayNode();
		ByteBuffer matrixByteBuffer = ByteBuffer.wrap(ifcProduct.getGeometry().getTransformation());
		matrixByteBuffer.order(ByteOrder.LITTLE_ENDIAN);
		DoubleBuffer doubleBuffer = matrixByteBuffer.asDoubleBuffer();
		for (int i = 0; i < 16; i++) {
			matrixArray.add(doubleBuffer.get(i));
		}

		ArrayNode meshes = OBJECT_MAPPER.createArrayNode();
		meshes.add(meshName);

		nodeNode.set("meshes", meshes);
		nodeNode.set("matrix", matrixArray);
		nodes.set(nodeName, nodeNode);

		return nodeName;
	}

	private ObjectNode createDefaultScene() {
		ObjectNode sceneNode = OBJECT_MAPPER.createObjectNode();

		defaultSceneNodes = OBJECT_MAPPER.createArrayNode();

		sceneNode.set("nodes", defaultSceneNodes);

		return sceneNode;
	}

	private String createBufferView(int byteLength, int byteOffset) {
		return createBufferView(byteLength, byteOffset, -1);
	}
	
	private String createBufferView(int byteLength, int byteOffset, int target) {
		String name = "bufferView_" + (bufferViewCounter++);
		ObjectNode bufferViewNode = OBJECT_MAPPER.createObjectNode();

		bufferViewNode.put("buffer", "binary_glTF");
		bufferViewNode.put("byteLength", byteLength);
		bufferViewNode.put("byteOffset", byteOffset);
		if (target != -1) {
			bufferViewNode.put("target", target);
		}

		buffersViews.set(name, bufferViewNode);

		return name;
	}

	private String addNormalsAccessor(IfcProduct ifcProduct, String bufferViewName, int byteOffset, int count) throws SerializerException {
		if (count <= 0) {
			throw new SerializerException("Count <= 0");
		}
		String accessorName = "accessor_normal_" + (accessorCounter++);

		ObjectNode accessor = OBJECT_MAPPER.createObjectNode();
		accessor.put("bufferView", bufferViewName);
		accessor.put("byteOffset", byteOffset);
		accessor.put("byteStride", 12);
		accessor.put("componentType", FLOAT);
		accessor.put("count", count);
		accessor.put("type", "VEC3");

		ArrayNode min = OBJECT_MAPPER.createArrayNode();
		min.add(-1d);
		min.add(-1d);
		min.add(-1d);
		ArrayNode max = OBJECT_MAPPER.createArrayNode();
		max.add(1);
		max.add(1);
		max.add(1);

		accessor.set("min", min);
		accessor.set("max", max);

		accessors.set(accessorName, accessor);

		return accessorName;
	}

	private String addColorsAccessor(IfcProduct ifcProduct, String bufferViewName, int byteOffset, int count) {
		String accessorName = "accessor_color_" + (accessorCounter++);
		
		ObjectNode accessor = OBJECT_MAPPER.createObjectNode();
		accessor.put("bufferView", bufferViewName);
		accessor.put("byteOffset", byteOffset);
		accessor.put("byteStride", 16);
		accessor.put("componentType", FLOAT);
		accessor.put("count", count);
		accessor.put("type", "VEC4");
		
//		ArrayNode min = OBJECT_MAPPER.createArrayNode();
//		min.add(-1d);
//		min.add(-1d);
//		min.add(-1d);
//		ArrayNode max = OBJECT_MAPPER.createArrayNode();
//		max.add(1);
//		max.add(1);
//		max.add(1);
//		
//		accessor.set("min", min);
//		accessor.set("max", max);
		
		accessors.set(accessorName, accessor);
		
		return accessorName;
	}

	private String addVerticesAccessor(IfcProduct ifcProduct, String bufferViewName, int startPosition, int count) throws SerializerException {
		if (count <= 0) {
			throw new SerializerException("Count <= 0");
		}
		String accessorName = "accessor_vertex_" + (accessorCounter++);

		GeometryData data = ifcProduct.getGeometry().getData();
		ByteBuffer verticesBuffer = ByteBuffer.wrap(data.getVertices().getData());

		ObjectNode accessor = OBJECT_MAPPER.createObjectNode();
		accessor.put("bufferView", bufferViewName);
		accessor.put("byteOffset", startPosition);
		accessor.put("byteStride", 12);
		accessor.put("componentType", FLOAT);
		accessor.put("count", count);
		accessor.put("type", "VEC3");

		verticesBuffer.order(ByteOrder.LITTLE_ENDIAN);
		double[] min = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
		double[] max = { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
		for (int i = 0; i < verticesBuffer.capacity(); i += 3) {
			for (int j = 0; j < 3; j++) {
				double val = verticesBuffer.get(i + j);
				if (val > max[j]) {
					max[j] = val;
				}
				if (val < min[j]) {
					min[j] = val;
				}
			}
		}

		ArrayNode minNode = OBJECT_MAPPER.createArrayNode();
		minNode.add(min[0]);
		minNode.add(min[1]);
		minNode.add(min[2]);
		ArrayNode maxNode = OBJECT_MAPPER.createArrayNode();
		maxNode.add(max[0]);
		maxNode.add(max[1]);
		maxNode.add(max[2]);

		accessor.set("min", minNode);
		accessor.set("max", maxNode);

		accessors.set(accessorName, accessor);

		return accessorName;
	}

	private String addIndicesAccessor(IfcProduct ifcProduct, String bufferViewName, int offsetBytes, int count) throws SerializerException {
		if (count <= 0) {
			throw new SerializerException(count + " <= 0");
		}
		String accessorName = "accessor_index_" + (accessorCounter++);

		ObjectNode accessor = OBJECT_MAPPER.createObjectNode();
		accessor.put("bufferView", bufferViewName);
		accessor.put("byteOffset", offsetBytes);
		accessor.put("byteStride", 0);
		accessor.put("componentType", UNSIGNED_SHORT);
		accessor.put("count", count);
		accessor.put("type", "SCALAR");

		accessors.set(accessorName, accessor);

		return accessorName;
	}

	private String addMesh(IfcProduct ifcProduct, ArrayNode primitivesNode) {
		ObjectNode meshNode = OBJECT_MAPPER.createObjectNode();
		String meshName = "mesh_" + ifcProduct.getOid();
		meshNode.put("name", meshName);
		meshNode.set("primitives", primitivesNode);
		meshes.set(meshName, meshNode);
		return meshName;
	}

	private void addBuffer(String name, String type, int byteLength) {
		ObjectNode bufferNode = OBJECT_MAPPER.createObjectNode();

		bufferNode.put("byteLength", byteLength);
		bufferNode.put("type", type);
		bufferNode.put("uri", "data:,");

		buffers.set(name, bufferNode);
	}

	private JsonNode createAnimations() {
		// TODO
		return OBJECT_MAPPER.createObjectNode();
	}

	private JsonNode createTechniques() {
		ObjectNode techniques = OBJECT_MAPPER.createObjectNode();

		techniques.set("vertexColorTechnique", createVertexColorTechnique());
		techniques.set("materialColorTechnique", createMaterialColorTechnique());

		return techniques;
	}

	private ObjectNode createMaterialColorTechnique() {
		ObjectNode technique = OBJECT_MAPPER.createObjectNode();

		ObjectNode attributes = OBJECT_MAPPER.createObjectNode();
		ObjectNode parameters = OBJECT_MAPPER.createObjectNode();
		ObjectNode states = OBJECT_MAPPER.createObjectNode();
		ObjectNode uniforms = OBJECT_MAPPER.createObjectNode();

		attributes.put("a_normal", "normal");
		attributes.put("a_position", "position");

		ObjectNode diffuse = OBJECT_MAPPER.createObjectNode();
		diffuse.put("type", 35666);
		parameters.set("diffuse", diffuse);

		ObjectNode modelViewMatrix = OBJECT_MAPPER.createObjectNode();
		modelViewMatrix.put("semantic", "MODELVIEW");
		modelViewMatrix.put("type", 35676);
		parameters.set("modelViewMatrix", modelViewMatrix);

		ObjectNode normal = OBJECT_MAPPER.createObjectNode();
		normal.put("semantic", "NORMAL");
		normal.put("type", 35665);
		parameters.set("normal", normal);

		ObjectNode normalMatrix = OBJECT_MAPPER.createObjectNode();
		normalMatrix.put("semantic", "MODELVIEWINVERSETRANSPOSE");
		normalMatrix.put("type", 35675);
		parameters.set("normalMatrix", normalMatrix);

		ObjectNode position = OBJECT_MAPPER.createObjectNode();
		position.put("semantic", "POSITION");
		position.put("type", 35665);
		parameters.set("position", position);

		ObjectNode projectionMatrix = OBJECT_MAPPER.createObjectNode();
		projectionMatrix.put("semantic", "PROJECTION");
		projectionMatrix.put("type", 35676);
		parameters.set("projectionMatrix", projectionMatrix);

		ObjectNode shininess = OBJECT_MAPPER.createObjectNode();
		shininess.put("type", 5126);
		parameters.set("shininess", shininess);

		ObjectNode specular = OBJECT_MAPPER.createObjectNode();
		specular.put("type", 35666);
		parameters.set("specular", specular);

		technique.put("program", "materialColorProgram");

		ArrayNode statesEnable = OBJECT_MAPPER.createArrayNode();
		statesEnable.add(2929);
		statesEnable.add(2884);
		states.set("enable", statesEnable);

		uniforms.put("u_diffuse", "diffuse");
		uniforms.put("u_modelViewMatrix", "modelViewMatrix");
		uniforms.put("u_normalMatrix", "normalMatrix");
		uniforms.put("u_projectionMatrix", "projectionMatrix");
		uniforms.put("u_shininess", "shininess");
		uniforms.put("u_specular", "specular");

		technique.set("attributes", attributes);
		technique.set("parameters", parameters);
		technique.set("states", states);
		technique.set("uniforms", uniforms);
		
		return technique;
	}

	private ObjectNode createVertexColorTechnique() {
		ObjectNode technique = OBJECT_MAPPER.createObjectNode();

		ObjectNode attributes = OBJECT_MAPPER.createObjectNode();
		ObjectNode parameters = OBJECT_MAPPER.createObjectNode();
		ObjectNode states = OBJECT_MAPPER.createObjectNode();
		ObjectNode uniforms = OBJECT_MAPPER.createObjectNode();

		attributes.put("a_normal", "normal");
		attributes.put("a_position", "position");
		attributes.put("a_color", "color");

		ObjectNode modelViewMatrix = OBJECT_MAPPER.createObjectNode();
		modelViewMatrix.put("semantic", "MODELVIEW");
		modelViewMatrix.put("type", 35676);
		parameters.set("modelViewMatrix", modelViewMatrix);

		ObjectNode normal = OBJECT_MAPPER.createObjectNode();
		normal.put("semantic", "NORMAL");
		normal.put("type", 35665);
		parameters.set("normal", normal);

		ObjectNode normalMatrix = OBJECT_MAPPER.createObjectNode();
		normalMatrix.put("semantic", "MODELVIEWINVERSETRANSPOSE");
		normalMatrix.put("type", 35675);
		parameters.set("normalMatrix", normalMatrix);

		ObjectNode position = OBJECT_MAPPER.createObjectNode();
		position.put("semantic", "POSITION");
		position.put("type", 35665);
		parameters.set("position", position);

		ObjectNode color = OBJECT_MAPPER.createObjectNode();
		color.put("semantic", "COLOR");
		color.put("type", FLOAT_VEC_4);
		parameters.set("color", color);

		ObjectNode projectionMatrix = OBJECT_MAPPER.createObjectNode();
		projectionMatrix.put("semantic", "PROJECTION");
		projectionMatrix.put("type", 35676);
		parameters.set("projectionMatrix", projectionMatrix);

		technique.put("program", "vertexColorProgram");

		ArrayNode statesEnable = OBJECT_MAPPER.createArrayNode();
		statesEnable.add(2929);
		statesEnable.add(2884);
		states.set("enable", statesEnable);

		uniforms.put("u_modelViewMatrix", "modelViewMatrix");
		uniforms.put("u_normalMatrix", "normalMatrix");
		uniforms.put("u_projectionMatrix", "projectionMatrix");

		technique.set("attributes", attributes);
		technique.set("parameters", parameters);
		technique.set("states", states);
		technique.set("uniforms", uniforms);
		
		return technique;
	}

	private JsonNode createSkins() {
		// TODO
		return OBJECT_MAPPER.createObjectNode();
	}

	private void createVertexColorShaders(String fragmentShaderBufferViewName, String vertexShaderBufferViewName) {
		ObjectNode fragmentShaderExtensions = OBJECT_MAPPER.createObjectNode();
		ObjectNode fragmentShaderBinary = OBJECT_MAPPER.createObjectNode();
		fragmentShaderExtensions.set("KHR_binary_glTF", fragmentShaderBinary);
		fragmentShaderBinary.put("bufferView", fragmentShaderBufferViewName);

		ObjectNode fragmentShader = OBJECT_MAPPER.createObjectNode();
		fragmentShader.put("type", 35632);
		fragmentShader.put("uri", "data:,");
		fragmentShader.set("extensions", fragmentShaderExtensions);

		ObjectNode vertexShaderExtensions = OBJECT_MAPPER.createObjectNode();
		ObjectNode vertexShaderBinary = OBJECT_MAPPER.createObjectNode();
		vertexShaderExtensions.set("KHR_binary_glTF", vertexShaderBinary);
		vertexShaderBinary.put("bufferView", vertexShaderBufferViewName);

		ObjectNode vertexShader = OBJECT_MAPPER.createObjectNode();
		vertexShader.put("type", 35633);
		vertexShader.put("uri", "data:,");
		vertexShader.set("extensions", vertexShaderExtensions);

		shaders.set("vertexColorFragmentShader", fragmentShader);
		shaders.set("vertexColorVertexShader", vertexShader);
	}

	private void createMaterialColorShaders(String fragmentShaderBufferViewName, String vertexShaderBufferViewName) {
		ObjectNode fragmentShaderExtensions = OBJECT_MAPPER.createObjectNode();
		ObjectNode fragmentShaderBinary = OBJECT_MAPPER.createObjectNode();
		fragmentShaderExtensions.set("KHR_binary_glTF", fragmentShaderBinary);
		fragmentShaderBinary.put("bufferView", fragmentShaderBufferViewName);

		ObjectNode fragmentShader = OBJECT_MAPPER.createObjectNode();
		fragmentShader.put("type", 35632);
		fragmentShader.put("uri", "data:,");
		fragmentShader.set("extensions", fragmentShaderExtensions);

		ObjectNode vertexShaderExtensions = OBJECT_MAPPER.createObjectNode();
		ObjectNode vertexShaderBinary = OBJECT_MAPPER.createObjectNode();
		vertexShaderExtensions.set("KHR_binary_glTF", vertexShaderBinary);
		vertexShaderBinary.put("bufferView", vertexShaderBufferViewName);

		ObjectNode vertexShader = OBJECT_MAPPER.createObjectNode();
		vertexShader.put("type", 35633);
		vertexShader.put("uri", "data:,");
		vertexShader.set("extensions", vertexShaderExtensions);

		shaders.set("materialColorFragmentShader", fragmentShader);
		shaders.set("materialColorVertexShader", vertexShader);
	}
	
	private ObjectNode createPrograms() {
		ObjectNode programs = OBJECT_MAPPER.createObjectNode();

		programs.set("vertexColorProgram", createVertexColorsPrograms());
		programs.set("materialColorProgram", createMaterialColorsPrograms());

		return programs;
	}

	private JsonNode createVertexColorsPrograms() {
		ObjectNode program = OBJECT_MAPPER.createObjectNode();
		ArrayNode attributes = OBJECT_MAPPER.createArrayNode();

		program.set("attributes", attributes);
		attributes.add("a_normal");
		attributes.add("a_position");
		attributes.add("a_color");

		program.put("fragmentShader", "vertexColorFragmentShader");
		program.put("vertexShader", "vertexColorVertexShader");
		
		return program;
	}

	private JsonNode createMaterialColorsPrograms() {
		ObjectNode program = OBJECT_MAPPER.createObjectNode();
		ArrayNode attributes = OBJECT_MAPPER.createArrayNode();

		program.set("attributes", attributes);
		attributes.add("a_normal");
		attributes.add("a_position");

		program.put("fragmentShader", "materialColorFragmentShader");
		program.put("vertexShader", "materialColorVertexShader");

		return program;
	}
	
	private String createOrGetMaterial(String name, float[] colors) {
		if (createdMaterials.contains(name)) {
			return name;
		}
		ObjectNode material = OBJECT_MAPPER.createObjectNode();

		material.put("name", name + "Material");
		material.put("technique", "materialColorTechnique");

		ObjectNode values = OBJECT_MAPPER.createObjectNode();

		ArrayNode diffuse = OBJECT_MAPPER.createArrayNode();
		for (int i=0; i<4; i++) {
			diffuse.add(colors[i]);
		}
//		diffuse.add(0.8000000119209291);
//		diffuse.add(0);
//		diffuse.add(0);
//		diffuse.add(1);

		ArrayNode specular = OBJECT_MAPPER.createArrayNode();
		specular.add(0.20000000298023218);
		specular.add(0.20000000298023218);
		specular.add(0.20000000298023218);

		values.set("diffuse", diffuse);
		values.set("specular", specular);
		values.put("shininess", 256);

		material.set("values", values);

		materials.set(name, material);
		
		createdMaterials.add(name);
		
		return name;
	}

	private void createVertexColorMaterial() {
		ObjectNode defaultMaterial = OBJECT_MAPPER.createObjectNode();
		
		defaultMaterial.put("name", VERTEX_COLOR_MATERIAL);
		defaultMaterial.put("technique", "vertexColorTechnique");
		
		ObjectNode values = OBJECT_MAPPER.createObjectNode();
		
		defaultMaterial.set("values", values);
		
		materials.set(VERTEX_COLOR_MATERIAL, defaultMaterial);
	}

	private JsonNode createAsset() {
		// TODO
		return OBJECT_MAPPER.createObjectNode();
	}

	private void writeHeader(LittleEndianDataOutputStream dataOutputStream, int headerLength, int sceneLength, int bodyLength) throws IOException {
		dataOutputStream.write(MAGIC.getBytes(Charsets.US_ASCII));
		dataOutputStream.writeInt(FORMAT_VERSION_1);
		dataOutputStream.writeInt(headerLength + sceneLength + bodyLength);
		dataOutputStream.writeInt(sceneLength);
		dataOutputStream.writeInt(JSON_SCENE_FORMAT);
	}

	private void writeBody(LittleEndianDataOutputStream dataOutputStream, byte[] body) throws IOException {
		dataOutputStream.write(body);
	}

	private void writeScene(LittleEndianDataOutputStream dataOutputStream, byte[] scene) throws IOException {
		dataOutputStream.write(scene);
	}
}
