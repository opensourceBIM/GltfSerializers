precision highp float;

attribute vec3 a_position;
attribute vec3 a_normal;
attribute vec4 a_color;
varying vec3 v_normal;
varying vec4 v_color;
uniform mat3 u_normalMatrix;
uniform mat4 u_modelViewMatrix;
uniform mat4 u_projectionMatrix;

void main(void) {
	vec4 pos = u_modelViewMatrix * vec4(a_position,1.0);
	v_normal = u_normalMatrix * a_normal;
	v_color = a_color;
	gl_Position = u_projectionMatrix * pos;
}