import { Matrix4 } from './Matrix4.js'

const VERT_SHADER = `#version 300 es
precision highp float;

in vec3 aPosition;
in float aIntensity;

uniform mat4 uModel;
uniform mat4 uView;
uniform mat4 uProjection;

out float vDepth;
out float vIntensity;
out vec2 vGridPos;

void main() {
  vDepth = aPosition.z;
  vIntensity = aIntensity;
  vGridPos = aPosition.xy;
  gl_Position = uProjection * uView * uModel * vec4(aPosition, 1.0);
}
`

const FRAG_SHADER = `#version 300 es
precision highp float;

in float vDepth;
in float vIntensity;
in vec2 vGridPos;

out vec4 fragColor;

vec3 oceanColor(float depth) {
  if (depth <= 50.0) {
    float t = depth / 50.0;
    return mix(vec3(0.0, 0.92, 0.95), vec3(0.0, 0.6, 0.8), t);
  } else if (depth <= 200.0) {
    float t = (depth - 50.0) / 150.0;
    return mix(vec3(0.0, 0.6, 0.8), vec3(0.0, 0.2, 0.5), t);
  } else if (depth <= 500.0) {
    float t = (depth - 200.0) / 300.0;
    return mix(vec3(0.0, 0.2, 0.5), vec3(0.05, 0.05, 0.25), t);
  } else {
    float t = clamp((depth - 500.0) / 500.0, 0.0, 1.0);
    return mix(vec3(0.05, 0.05, 0.25), vec3(0.02, 0.02, 0.1), t);
  }
}

void main() {
  vec3 baseColor = oceanColor(vDepth);
  baseColor *= (0.3 + 0.7 * vIntensity);

  vec2 grid = abs(fract(vGridPos * 2.0 - 0.5) - 0.5);
  float line = min(grid.x, grid.y);
  float gridLine = 1.0 - smoothstep(0.0, 0.02, line);
  baseColor = mix(baseColor, baseColor + vec3(0.08, 0.12, 0.18), gridLine * 0.4);

  fragColor = vec4(baseColor, 1.0);
}
`

const GRID_SIZE = 256
const VERT_FLOATS = 4
const STRIDE = VERT_FLOATS * 4

export class HeightmapRenderer {
  constructor(canvas) {
    this.canvas = canvas
    this.gl = canvas.getContext('webgl2', { antialias: true, alpha: false })
    if (!this.gl) throw new Error('WebGL2 not supported')

    this.azimuth = 0.6
    this.elevation = 0.5
    this.distance = 3.5
    this.wireframe = false
    this.dirty = true
    this.running = false
    this._rafId = null

    this._initShaders()
    this._initGrid()
    this._initCamera()
    this._resize()
  }

  _compileShader(source, type) {
    const gl = this.gl
    const shader = gl.createShader(type)
    gl.shaderSource(shader, source)
    gl.compileShader(shader)
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
      const info = gl.getShaderInfoLog(shader)
      gl.deleteShader(shader)
      throw new Error('Shader compile error: ' + info)
    }
    return shader
  }

  _initShaders() {
    const gl = this.gl
    const vs = this._compileShader(VERT_SHADER, gl.VERTEX_SHADER)
    const fs = this._compileShader(FRAG_SHADER, gl.FRAGMENT_SHADER)

    this.program = gl.createProgram()
    gl.attachShader(this.program, vs)
    gl.attachShader(this.program, fs)
    gl.linkProgram(this.program)

    if (!gl.getProgramParameter(this.program, gl.LINK_STATUS)) {
      throw new Error('Program link error: ' + gl.getProgramInfoLog(this.program))
    }

    gl.deleteShader(vs)
    gl.deleteShader(fs)

    this.aPosition = gl.getAttribLocation(this.program, 'aPosition')
    this.aIntensity = gl.getAttribLocation(this.program, 'aIntensity')
    this.uModel = gl.getUniformLocation(this.program, 'uModel')
    this.uView = gl.getUniformLocation(this.program, 'uView')
    this.uProjection = gl.getUniformLocation(this.program, 'uProjection')
  }

  _initGrid() {
    const gl = this.gl
    const vertexCount = GRID_SIZE * GRID_SIZE
    this.vertexData = new Float32Array(vertexCount * VERT_FLOATS)
    this.vertexCount = vertexCount

    for (let row = 0; row < GRID_SIZE; row++) {
      for (let col = 0; col < GRID_SIZE; col++) {
        const idx = (row * GRID_SIZE + col) * VERT_FLOATS
        this.vertexData[idx + 0] = (col / (GRID_SIZE - 1)) - 0.5
        this.vertexData[idx + 1] = (row / (GRID_SIZE - 1)) - 0.5
        this.vertexData[idx + 2] = 0.0
        this.vertexData[idx + 3] = 0.5
      }
    }

    this.vbo = gl.createBuffer()
    gl.bindBuffer(gl.ARRAY_BUFFER, this.vbo)
    gl.bufferData(gl.ARRAY_BUFFER, this.vertexData, gl.DYNAMIC_DRAW)

    this.vao = gl.createVertexArray()
    gl.bindVertexArray(this.vao)
    gl.bindBuffer(gl.ARRAY_BUFFER, this.vbo)

    gl.enableVertexAttribArray(this.aPosition)
    gl.vertexAttribPointer(this.aPosition, 3, gl.FLOAT, false, STRIDE, 0)

    gl.enableVertexAttribArray(this.aIntensity)
    gl.vertexAttribPointer(this.aIntensity, 1, gl.FLOAT, false, STRIDE, 12)

    this._buildIndexBuffer()
    gl.bindVertexArray(null)
  }

  _buildIndexBuffer() {
    const gl = this.gl
    const rows = GRID_SIZE - 1
    const cols = GRID_SIZE - 1
    const indexCount = rows * cols * 6
    const indices = new Uint32Array(indexCount)

    let ptr = 0
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        const tl = r * GRID_SIZE + c
        const tr = tl + 1
        const bl = tl + GRID_SIZE
        const br = bl + 1
        indices[ptr++] = tl
        indices[ptr++] = bl
        indices[ptr++] = tr
        indices[ptr++] = tr
        indices[ptr++] = bl
        indices[ptr++] = br
      }
    }

    this.indexCount = indexCount
    this.ibo = gl.createBuffer()
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, this.ibo)
    gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, indices, gl.STATIC_DRAW)
  }

  _initCamera() {
    this.modelMatrix = Matrix4.identity()
  }

  updateDepthData(frame) {
    if (!frame || !frame.points || frame.points.length === 0) return

    const points = frame.points
    const gridExtent = GRID_SIZE - 1

    let minX = Infinity, maxX = -Infinity
    let minY = Infinity, maxY = -Infinity
    let maxAbsZ = 0

    for (let i = 0; i < points.length; i++) {
      const p = points[i]
      if (p.x < minX) minX = p.x
      if (p.x > maxX) maxX = p.x
      if (p.y < minY) minY = p.y
      if (p.y > maxY) maxY = p.y
      const absZ = Math.abs(p.z)
      if (absZ > maxAbsZ) maxAbsZ = absZ
    }

    const rangeX = maxX - minX || 1
    const rangeY = maxY - minY || 1
    const zScale = maxAbsZ > 0 ? 0.3 / maxAbsZ : 0.001

    for (let i = 0; i < points.length; i++) {
      const p = points[i]
      const nx = (p.x - minX) / rangeX
      const ny = (p.y - minY) / rangeY
      const gx = Math.round(nx * gridExtent)
      const gy = Math.round(ny * gridExtent)

      if (gx < 0 || gx >= GRID_SIZE || gy < 0 || gy >= GRID_SIZE) continue

      const depth = p.z
      const intensity = p.intensity !== undefined ? Math.max(0, Math.min(1, (p.intensity + 60) / 60)) : 0.5
      const idx = (gy * GRID_SIZE + gx) * VERT_FLOATS

      this.vertexData[idx + 2] = -depth * zScale
      this.vertexData[idx + 3] = intensity

      const spread = Math.min(2, Math.max(1, Math.floor(gridExtent / 128)))
      for (let dy = -spread; dy <= spread; dy++) {
        for (let dx = -spread; dx <= spread; dx++) {
          if (dx === 0 && dy === 0) continue
          const nx2 = gx + dx
          const ny2 = gy + dy
          if (nx2 < 0 || nx2 >= GRID_SIZE || ny2 < 0 || ny2 >= GRID_SIZE) continue
          const nidx = (ny2 * GRID_SIZE + nx2) * VERT_FLOATS
          const w = 1.0 / (1.0 + Math.abs(dx) + Math.abs(dy))
          const oldZ = this.vertexData[nidx + 2]
          if (oldZ === 0.0) {
            this.vertexData[nidx + 2] = -depth * zScale * w
            this.vertexData[nidx + 3] = intensity * w
          }
        }
      }
    }

    const gl = this.gl
    gl.bindBuffer(gl.ARRAY_BUFFER, this.vbo)
    gl.bufferSubData(gl.ARRAY_BUFFER, 0, this.vertexData)
    this.dirty = true
  }

  resetCamera() {
    this.azimuth = 0.6
    this.elevation = 0.5
    this.distance = 3.5
    this.dirty = true
  }

  _computeViewMatrix() {
    const cx = Math.sin(this.azimuth) * Math.cos(this.elevation) * this.distance
    const cy = Math.cos(this.azimuth) * Math.cos(this.elevation) * this.distance
    const cz = Math.sin(this.elevation) * this.distance
    return Matrix4.lookAt([cx, cy, cz], [0, 0, -0.2], [0, 0, 1])
  }

  _resize() {
    const dpr = window.devicePixelRatio || 1
    const w = this.canvas.clientWidth * dpr
    const h = this.canvas.clientHeight * dpr
    if (this.canvas.width !== w || this.canvas.height !== h) {
      this.canvas.width = w
      this.canvas.height = h
      this.dirty = true
    }
  }

  render() {
    this._resize()
    if (!this.dirty) {
      this._rafId = requestAnimationFrame(() => this.render())
      return
    }
    this.dirty = false

    const gl = this.gl
    const width = this.canvas.width
    const height = this.canvas.height

    gl.viewport(0, 0, width, height)
    gl.clearColor(0.039, 0.055, 0.09, 1.0)
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT)
    gl.enable(gl.DEPTH_TEST)
    gl.enable(gl.CULL_FACE)
    gl.cullFace(gl.BACK)

    gl.useProgram(this.program)

    const projection = Matrix4.perspective(Math.PI / 3, width / height, 0.1, 1000)
    const view = this._computeViewMatrix()

    gl.uniformMatrix4fv(this.uProjection, false, projection.m)
    gl.uniformMatrix4fv(this.uView, false, view.m)
    gl.uniformMatrix4fv(this.uModel, false, this.modelMatrix.m)

    gl.bindVertexArray(this.vao)

    if (this.wireframe) {
      for (let r = 0; r < GRID_SIZE - 1; r++) {
        const start = r * (GRID_SIZE - 1) * 6
        const count = (GRID_SIZE - 1) * 6
        gl.drawElements(gl.LINE_STRIP, count, gl.UNSIGNED_INT, start * 4)
      }
    } else {
      gl.drawElements(gl.TRIANGLES, this.indexCount, gl.UNSIGNED_INT, 0)
    }

    gl.bindVertexArray(null)
    this._rafId = requestAnimationFrame(() => this.render())
  }

  start() {
    if (this.running) return
    this.running = true
    this.dirty = true
    this._rafId = requestAnimationFrame(() => this.render())
  }

  stop() {
    this.running = false
    if (this._rafId) {
      cancelAnimationFrame(this._rafId)
      this._rafId = null
    }
  }

  destroy() {
    this.stop()
    const gl = this.gl
    if (this.vbo) gl.deleteBuffer(this.vbo)
    if (this.ibo) gl.deleteBuffer(this.ibo)
    if (this.vao) gl.deleteVertexArray(this.vao)
    if (this.program) gl.deleteProgram(this.program)
  }
}
