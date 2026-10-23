// The Camera (SharedCamera.hpp) for a Style that draws its own 3D world.
//
// A Style that reads uCamRot owns its camera: ShaderScene sees the uniform and the composite then leaves
// push-in, orbit and travel to the Style instead of moving its finished picture the flat way. Every
// value glides (CameraRig), and at the Camera's starting values uCamRot is the identity and the rest 0,
// so the Style draws exactly as it did.
//
// The world's subject sits at the origin and the Style's own camera looks at it along +z.
uniform mat3 uCamRot;     // the orbit: yaw about the vertical, then pitch
uniform float uCamPush;   // 0..1: how far the camera has pushed in toward the subject
uniform float uCamTravel; // world units flown along the Style's flight axis (+z), either way
uniform vec2 uCamOrbit;   // the orbit's angles in radians (x yaw, y pitch)

// The Camera on a ray aimed at a subject at the origin: orbit around it, push toward it.
void cameraOrbit(inout vec3 ro, inout vec3 rd) {
    ro = uCamRot * (ro * (1.0 - 0.6 * uCamPush));
    rd = uCamRot * rd;
}

// The Camera on a Style that flies (a tunnel, a corridor, open space): the orbit turns the view, push-in
// moves ahead, and travel adds to the Style's own flight.
void cameraFlight(inout vec3 ro, inout vec3 rd) {
    rd = uCamRot * rd;
    ro.z += uCamTravel + uCamPush * 1.5;
}
