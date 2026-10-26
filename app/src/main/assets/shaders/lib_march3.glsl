// The march itself, for a Style that raymarches a world: a faster sphere trace, glow gathered along
// the ray, cheap ambient occlusion and fog. lib_sdf3 is the geometry; this is how a ray walks it.
//
// Nothing here reads a uniform, and nothing calls map(): GLSL ES has no function pointers, so the
// loop stays in the Style and these helpers are what its body calls. The shape of a march with them:
//
//     March3 m = marchStart(t0, 1.5, 0.02);
//     for (int i = 0; i < 128; i++) {             // the compile-time cap (lib_sdf3's RAYMARCH note)
//         if (float(i) >= uSteps) break;          // the Detail budget
//         float d = map(ro + rd * m.t);
//         glow += glowLine(gWire, 0.03, m.step);  // anything the ray passes close to, as light
//         if (marchStep(m, d, cone) || m.t > far) break;
//     }
//
// ---- the faster march, and why it stays safe ------------------------------
//
// A plain sphere trace steps exactly the distance the field promises is empty, so a ray running
// parallel to a wall crawls along it in ever smaller steps. Two things lengthen the steps here:
//
//   - OVER-RELAXATION: the step is `omega` times the promised distance (omega up to 2). Past the
//     promise nothing is guaranteed, so each step is checked after it is taken: the sphere promised
//     empty at the last point and the one promised at this point must overlap, or together they
//     cover the whole step. When they do not, a surface may lie in the gap, so the ray goes back to
//     the last point, takes the plain step from there, and marches plainly from then on.
//   - RELAXATION BY DISTANCE: the over-step grows with how far the ray has gone (`relax` per world
//     unit), since a far ray is a wide cone and only needs to find its surface to a wide tolerance.
//     The same check covers it: any step, however chosen, is accepted only if the two spheres cover it.
//
// The check needs the field to be a true bound (never more than the real distance). A field that is
// not, a twist or a fold that stretches space, must be divided by its Lipschitz bound first, as
// lib_sdf3 says; over-relaxation on an overestimating field steps through thin parts twice as fast.
//
// The hit tolerance is a cone: a pixel at distance t covers `cone * t` of world, and a surface closer
// than half of that is as found as it will ever be. A far wall stops the ray early instead of spending
// the budget resolving detail no pixel can show.

struct March3 {
    float t;      // how far along the ray
    float step;   // the step just taken (0 before the first): the length a glow sample stands for
    float omega;  // the over-relaxation; drops to 1 for good after a step that had to be taken back
    float relax;  // extra over-step per world unit of t
    float prev;   // the distance promised at the last point
    float minD;   // the closest the ray came to a surface, relative to the cone (for soft edges)
    bool hit;
};

/** A march from `t0` with over-relaxation `omega` (1 is a plain trace, 1.2 to 1.8 is typical). */
March3 marchStart(float t0, float omega, float relax) {
    return March3(t0, 0.0, clamp(omega, 1.0, 2.0), max(relax, 0.0), 0.0, 1e9, false);
}

/** The surface tolerance at `t` for a pixel cone of `cone` (radians per pixel, about 2 / (height x focal)). */
float marchEpsilon(float t, float cone) {
    return 0.5 * cone * t + 1e-4;
}

/**
 * One step of the march: `d` is map() at m.t. Returns true once the ray has found its surface (m.hit
 * set, m.t on it). Otherwise it moves m.t on, by an over-relaxed step or, when the last over-step left
 * a gap, back to the safe point.
 */
bool marchStep(inout March3 m, float d, float cone) {
    float r = abs(d);
    // The last step overshot if it stepped into a solid or the two empty spheres leave a gap.
    if (m.omega > 1.0 && m.step > 0.0 && (d < 0.0 || m.prev + r < m.step)) {
        m.t += m.prev - m.step;
        m.step = m.prev;
        m.omega = 1.0;
        m.relax = 0.0;
        // m.prev is still the promise at the point stepped back to; the next call steps from there.
        return false;
    }
    float eps = marchEpsilon(m.t, cone);
    m.minD = min(m.minD, r / eps);
    if (d < eps) {
        m.hit = true;
        return true;
    }
    float s = d * m.omega * (1.0 + m.relax * m.t);
    m.prev = d;
    m.step = s;
    m.t += s;
    return false;
}

// ---- glow along the ray ---------------------------------------------------

/**
 * Light from something thin the ray passes, gathered over the step `dt` the ray took at distance `d`
 * from it: the emission of a line with a core `w` wide falls as w / (w^2 + d^2). Summed along a ray
 * that passes a long straight line at closest distance b, it comes to pi * w / sqrt(w^2 + b^2): pi
 * on the line, falling as 1 / b. That is the neon halo, in units where the bare line reads 1.
 *
 * The step is capped at a few core widths per sample, so the sum does not swing with how a big
 * over-relaxed step happens to straddle the line.
 */
float glowLine(float d, float w, float dt) {
    float ww = w * w;
    return min(dt, 4.0 * max(abs(d), w)) * w / (ww + d * d) * 0.31830989;
}

// ---- ambient occlusion ----------------------------------------------------
//
// Occlusion read from the field near the surface. Sample map() a little way out along the normal: a
// point h out from an open surface sees the field return h, and anything nearby returns less. The
// shortfall, weighted down the further out it is measured, is how enclosed the point is. Three to
// five taps; the loop is the Style's, since it calls map():
//
//     float occ = 0.0;
//     for (int k = 0; k < 4; k++) {
//         float h = aoHeight(k, 0.6);
//         occ += aoTap(k, h, map(p + n * h));
//     }
//     float ao = aoResolve(occ, 1.0);

/** Where tap `k` samples, out to about `reach` world units for the fourth tap. */
float aoHeight(int k, float reach) {
    float f = (float(k) + 1.0) * 0.25;
    return reach * (0.05 + 0.95 * f * f);
}

/** Tap `k`'s share of the occlusion: the field's shortfall `h - d`, halved with each tap outward. */
float aoTap(int k, float h, float d) {
    return max(h - d, 0.0) / h * exp2(-float(k));
}

/** The taps' sum as light, 1 open and 0 shut; `strength` 1 is the usual depth. */
float aoResolve(float occ, float strength) {
    return clamp(1.0 - 0.55 * strength * occ, 0.0, 1.0);
}

/**
 * Occlusion for free: a ray that needed many steps grazed things on its way in, which is roughly
 * where creases and corners are. Cruder than the taps; for when the taps cost too much.
 */
float aoFromSteps(float steps, float budget) {
    return 1.0 - 0.7 * clamp(steps / max(budget, 1.0), 0.0, 1.0);
}

// ---- fog ------------------------------------------------------------------

/** Fog over `t` world units of a uniform medium of `density`: the share of the light it takes. */
float fogDistance(float t, float density) {
    return 1.0 - exp(-max(t, 0.0) * density);
}

/**
 * Fog of a medium thinning upward, `density` at height `base`, falling e-fold every 1/`falloff`
 * units of height: the share of the light it takes between ro and ro + rd * t.
 *
 * The integral is closed form. Density along the ray is k * exp(-falloff * (y0 + rd.y * s)), so its
 * integral over s from 0 to t is k * exp(-falloff * y0) * (1 - exp(-falloff * rd.y * t)) / (falloff
 * * rd.y); for a level ray the bracket over rd.y tends to t, which the guard below takes.
 */
float fogHeight(vec3 ro, vec3 rd, float t, float base, float density, float falloff) {
    float k = density * exp(-falloff * (ro.y - base));
    float b = falloff * rd.y;
    float path = abs(b) > 1e-4 ? (1.0 - exp(-b * t)) / b : t;
    return 1.0 - exp(-k * max(path, 0.0));
}

/**
 * The colour fog takes on looking along `rd` with a light toward `toLight`: the medium scatters
 * forward, so it brightens toward the light. `g` 0 is even all round, toward 1 a tight halo: the
 * lobe is 1 looking straight at the light and (1 - g) / (1 + g) looking across it.
 */
vec3 fogColor(vec3 rd, vec3 toLight, vec3 medium, vec3 light, float g) {
    float c = max(dot(rd, toLight), 0.0);
    g = clamp(g, 0.0, 0.95);
    float lobe = (1.0 - g) / (1.0 + g - 2.0 * g * c);
    return medium + light * lobe * 0.25;
}
