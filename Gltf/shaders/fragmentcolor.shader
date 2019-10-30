precision highp float;

varying vec3 v_normal;
varying vec4 v_color;
uniform vec4 u_diffuse;
uniform vec4 u_specular;
uniform float u_shininess;

void main(void) {
	vec3 normal = normalize(v_normal);
	gl_FragColor = v_color;
}