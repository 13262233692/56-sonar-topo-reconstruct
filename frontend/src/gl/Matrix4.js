export class Matrix4 {
  constructor() {
    this.m = new Float32Array(16)
    this.identity()
  }

  identity() {
    this.m.fill(0)
    this.m[0] = 1
    this.m[5] = 1
    this.m[10] = 1
    this.m[15] = 1
    return this
  }

  static identity() {
    return new Matrix4()
  }

  static perspective(fovY, aspect, near, far) {
    const out = new Matrix4()
    out.m.fill(0)
    const f = 1.0 / Math.tan(fovY / 2)
    const nf = 1 / (near - far)
    out.m[0] = f / aspect
    out.m[5] = f
    out.m[10] = (far + near) * nf
    out.m[11] = -1
    out.m[14] = 2 * far * near * nf
    return out
  }

  static lookAt(eye, center, up) {
    const out = new Matrix4()
    let zx = eye[0] - center[0]
    let zy = eye[1] - center[1]
    let zz = eye[2] - center[2]
    let len = Math.sqrt(zx * zx + zy * zy + zz * zz)
    if (len > 0) { len = 1 / len; zx *= len; zy *= len; zz *= len }

    let xx = up[1] * zz - up[2] * zy
    let xy = up[2] * zx - up[0] * zz
    let xz = up[0] * zy - up[1] * zx
    len = Math.sqrt(xx * xx + xy * xy + xz * xz)
    if (len > 0) { len = 1 / len; xx *= len; xy *= len; xz *= len }

    let yx = zy * xz - zz * xy
    let yy = zz * xx - zx * xz
    let yz = zx * xy - zy * xx

    out.m[0] = xx; out.m[1] = yx; out.m[2] = zx; out.m[3] = 0
    out.m[4] = xy; out.m[5] = yy; out.m[6] = zy; out.m[7] = 0
    out.m[8] = xz; out.m[9] = yz; out.m[10] = zz; out.m[11] = 0
    out.m[12] = -(xx * eye[0] + xy * eye[1] + xz * eye[2])
    out.m[13] = -(yx * eye[0] + yy * eye[1] + yz * eye[2])
    out.m[14] = -(zx * eye[0] + zy * eye[1] + zz * eye[2])
    out.m[15] = 1
    return out
  }

  static multiply(a, b) {
    const out = new Matrix4()
    const am = a.m
    const bm = b.m
    const om = out.m
    for (let i = 0; i < 4; i++) {
      for (let j = 0; j < 4; j++) {
        om[j * 4 + i] =
          am[i] * bm[j * 4] +
          am[4 + i] * bm[j * 4 + 1] +
          am[8 + i] * bm[j * 4 + 2] +
          am[12 + i] * bm[j * 4 + 3]
      }
    }
    return out
  }

  static rotateX(rad) {
    const out = new Matrix4()
    const s = Math.sin(rad)
    const c = Math.cos(rad)
    out.m[5] = c; out.m[6] = s
    out.m[9] = -s; out.m[10] = c
    return out
  }

  static rotateY(rad) {
    const out = new Matrix4()
    const s = Math.sin(rad)
    const c = Math.cos(rad)
    out.m[0] = c; out.m[2] = -s
    out.m[8] = s; out.m[10] = c
    return out
  }

  static translate(tx, ty, tz) {
    const out = new Matrix4()
    out.m[12] = tx
    out.m[13] = ty
    out.m[14] = tz
    return out
  }

  static scale(sx, sy, sz) {
    const out = new Matrix4()
    out.m[0] = sx
    out.m[5] = sy
    out.m[10] = sz
    return out
  }
}
