import { Matrix4 } from './Matrix4.js'
import { DelaunayTriangulation } from './DelaunayTriangulation.js'
import { HoleFiller } from './HoleFiller.js'

const VERT_SHADER = `#version 300 es
precision highp float;

in vec3 aPosition;
in float aIntensity;
in float aIsInterpolated;

uniform mat4 uModel;
uniform mat4 uView;
uniform mat4 uProjection;

out float vDepth;
out float vIntensity;
out float vIsInterpolated;
out vec3 vBarycentric;

void main() {
  vDepth = aPosition.z;
  vIntensity = aIntensity;
  vIsInterpolated = aIsInterpolated;
  vBarycentric = vec3(0.0);
  gl_Position = uProjection * uView * uModel * vec4(aPosition, 1.0);
}
`

const FRAG_SHADER = `#version 300 es
precision highp float;

in float vDepth;
in float vIntensity;
in float vIsInterpolated;

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
  vec3 baseColor = oceanColor(abs(vDepth));
  baseColor *= (0.3 + 0.7 * vIntensity);

  if (vIsInterpolated > 0.5) {
    baseColor *= 0.85;
  }

  fragColor = vec4(baseColor, 1.0);
}
`

const WIREFRAME_VERT_SHADER = `#version 300 es
precision highp float;

in vec3 aPosition;
in float aIntensity;
in float aIsInterpolated;

uniform mat4 uModel;
uniform mat4 uView;
uniform mat4 uProjection;

out float vDepth;
out float vIntensity;
out vec3 vBarycentric;

void main() {
  vDepth = aPosition.z;
  vIntensity = aIntensity;
  int vertIndex = gl_VertexID % 3;
  if (vertIndex == 0) vBarycentric = vec3(1.0, 0.0, 0.0);
  else if (vertIndex == 1) vBarycentric = vec3(0.0, 1.0, 0.0);
  else vBarycentric = vec3(0.0, 0.0, 1.0);
  gl_Position = uProjection * uView * uModel * vec4(aPosition, 1.0);
}
`

const WIREFRAME_FRAG_SHADER = `#version 300 es
precision highp float;

in float vDepth;
in float vIntensity;
in vec3 vBarycentric;

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
  vec3 baseColor = oceanColor(abs(vDepth));
  baseColor *= (0.3 + 0.7 * vIntensity);

  float edgeFactor = min(min(vBarycentric.x, vBarycentric.y), vBarycentric.z);
  float wireAlpha = 1.0 - smoothstep(0.0, 0.05, edgeFactor);

  vec3 wireColor = baseColor + vec3(0.15, 0.25, 0.35);
  baseColor = mix(baseColor * 0.5, wireColor, wireAlpha);

  fragColor = vec4(baseColor, 1.0);
}
`

const VERT_FLOATS = 5
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

    this._accumulatedPoints = []
    this._maxAccumulatedPoints = 8000
    this._frameCount = 0
    this._triangulationDirty = true
    this._lastMeshVertices = null
    this._lastMeshIndices = null

    this._delaunay = new DelaunayTriangulation()
    this._holeFiller = new HoleFiller()

    this._initShaders()
    this._initEmptyBuffers()
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

  _linkProgram(vsSource, fsSource) {
    const gl = this.gl
    const vs = this._compileShader(vsSource, gl.VERTEX_SHADER)
    const fs = this._compileShader(fsSource, gl.FRAGMENT_SHADER)
    const prog = gl.createProgram()
    gl.attachShader(prog, vs)
    gl.attachShader(prog, fs)
    gl.linkProgram(prog)
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
      throw new Error('Program link error: ' + gl.getProgramInfoLog(prog))
    }
    gl.deleteShader(vs)
    gl.deleteShader(fs)

    const result = {
      program: prog,
      aPosition: gl.getAttribLocation(prog, 'aPosition'),
      aIntensity: gl.getAttribLocation(prog, 'aIntensity'),
      aIsInterpolated: gl.getAttribLocation(prog, 'aIsInterpolated'),
      uModel: gl.getUniformLocation(prog, 'uModel'),
      uView: gl.getUniformLocation(prog, 'uView'),
      uProjection: gl.getUniformLocation(prog, 'uProjection')
    }
    return result
  }

  _initShaders() {
    this.solidProgram = this._linkProgram(VERT_SHADER, FRAG_SHADER)
    this.wireProgram = this._linkProgram(WIREFRAME_VERT_SHADER, WIREFRAME_FRAG_SHADER)
  }

  _initEmptyBuffers() {
    const gl = this.gl

    this.vbo = gl.createBuffer()
    gl.bindBuffer(gl.ARRAY_BUFFER, this.vbo)
    gl.bufferData(gl.ARRAY_BUFFER, 1, gl.DYNAMIC_DRAW)

    this.ibo = gl.createBuffer()
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, this.ibo)
    gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, 1, gl.DYNAMIC_DRAW)

    this.solidVao = gl.createVertexArray()
    gl.bindVertexArray(this.solidVao)
    gl.bindBuffer(gl.ARRAY_BUFFER, this.vbo)
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, this.ibo)
    gl.enableVertexAttribArray(this.solidProgram.aPosition)
    gl.vertexAttribPointer(this.solidProgram.aPosition, 3, gl.FLOAT, false, STRIDE, 0)
    gl.enableVertexAttribArray(this.solidProgram.aIntensity)
    gl.vertexAttribPointer(this.solidProgram.aIntensity, 1, gl.FLOAT, false, STRIDE, 12)
    gl.enableVertexAttribArray(this.solidProgram.aIsInterpolated)
    gl.vertexAttribPointer(this.solidProgram.aIsInterpolated, 1, gl.FLOAT, false, STRIDE, 16)
    gl.bindVertexArray(null)

    this.wireVao = gl.createVertexArray()
    gl.bindVertexArray(this.wireVao)
    gl.bindBuffer(gl.ARRAY_BUFFER, this.vbo)
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, this.ibo)
    gl.enableVertexAttribArray(this.wireProgram.aPosition)
    gl.vertexAttribPointer(this.wireProgram.aPosition, 3, gl.FLOAT, false, STRIDE, 0)
    gl.enableVertexAttribArray(this.wireProgram.aIntensity)
    gl.vertexAttribPointer(this.wireProgram.aIntensity, 1, gl.FLOAT, false, STRIDE, 12)
    gl.enableVertexAttribArray(this.wireProgram.aIsInterpolated)
    gl.vertexAttribPointer(this.wireProgram.aIsInterpolated, 1, gl.FLOAT, false, STRIDE, 16)
    gl.bindVertexArray(null)

    this.indexCount = 0
    this.vertexCount = 0
  }

  _initCamera() {
    this.modelMatrix = Matrix4.identity()
  }

  updateDepthData(frame) {
    if (!frame || !frame.points || frame.points.length === 0) return

    const points = frame.points
    this._frameCount++

    const appendCount = Math.min(points.length, this._maxAccumulatedPoints - this._accumulatedPoints.length)
    for (let i = 0; i < appendCount; i++) {
      const p = points[i]
      const intensity = p.intensity !== undefined ? Math.max(0, Math.min(1, (p.intensity + 60) / 60)) : 0.5
      this._accumulatedPoints.push({
        x: p.x,
        y: p.y,
        z: p.z,
        intensity: intensity,
        beamIndex: p.beamIndex !== undefined ? p.beamIndex : i,
        isInterpolated: false
      })
    }

    if (this._accumulatedPoints.length > this._maxAccumulatedPoints) {
      this._accumulatedPoints = this._accumulatedPoints.slice(
        this._accumulatedPoints.length - this._maxAccumulatedPoints
      )
    }

    this._triangulationDirty = true
    this._rebuildMesh()
  }

  _rebuildMesh() {
    if (!this._triangulationDirty) return
    if (this._accumulatedPoints.length < 3) return

    this._triangulationDirty = false

    const rawPoints = this._accumulatedPoints

    let minX = Infinity, maxX = -Infinity
    let minY = Infinity, maxY = -Infinity
    let maxAbsZ = 0

    for (let i = 0; i < rawPoints.length; i++) {
      const p = rawPoints[i]
      if (p.x < minX) minX = p.x
      if (p.x > maxX) maxX = p.x
      if (p.y < minY) minY = p.y
      if (p.y > maxY) maxY = p.y
      const absZ = Math.abs(p.z)
      if (absZ > maxAbsZ) maxAbsZ = absZ
    }

    const rangeX = maxX - minX || 1
    const rangeY = maxY - minY || 1
    this._normalization = { minX, minY, rangeX, rangeY, maxAbsZ }

    const zScale = maxAbsZ > 0 ? 0.3 / maxAbsZ : 0.001

    const gridDensity = Math.max(6, Math.min(20, Math.floor(Math.sqrt(rawPoints.length) / 4)))
    const filledPoints = this._holeFiller.fill(rawPoints, gridDensity)

    const normalizedPoints = []
    for (let i = 0; i < filledPoints.length; i++) {
      const p = filledPoints[i]
      normalizedPoints.push({
        x: (p.x - minX) / rangeX - 0.5,
        y: (p.y - minY) / rangeY - 0.5,
        z: -p.z * zScale,
        intensity: p.intensity !== undefined ? p.intensity : 0.5,
        isInterpolated: p.isInterpolated === true
      })
    }

    const mesh = this._delaunay.triangulate(normalizedPoints)

    if (mesh.indices.length === 0) return

    this._lastMeshVertices = mesh.vertices
    this._lastMeshIndices = mesh.indices
    this.indexCount = mesh.indices.length
    this.vertexCount = mesh.vertices.length / VERT_FLOATS

    const gl = this.gl
    gl.bindBuffer(gl.ARRAY_BUFFER, this.vbo)
    gl.bufferData(gl.ARRAY_BUFFER, mesh.vertices, gl.DYNAMIC_DRAW)
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, this.ibo)
    gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, mesh.indices, gl.STATIC_DRAW)

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

    const projection = Matrix4.perspective(Math.PI / 3, width / height, 0.1, 1000)
    const view = this._computeViewMatrix()

    if (this.indexCount > 0) {
      let prog, vao
      if (this.wireframe) {
        prog = this.wireProgram
        vao = this.wireVao
      } else {
        prog = this.solidProgram
        vao = this.solidVao
        gl.enable(gl.CULL_FACE)
        gl.cullFace(gl.BACK)
      }

      gl.useProgram(prog.program)
      gl.uniformMatrix4fv(prog.uProjection, false, projection.m)
      gl.uniformMatrix4fv(prog.uView, false, view.m)
      gl.uniformMatrix4fv(prog.uModel, false, this.modelMatrix.m)

      gl.bindVertexArray(vao)
      gl.drawElements(gl.TRIANGLES, this.indexCount, gl.UNSIGNED_INT, 0)
      gl.bindVertexArray(null)
    }

    gl.disable(gl.CULL_FACE)
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
    if (this.solidVao) gl.deleteVertexArray(this.solidVao)
    if (this.wireVao) gl.deleteVertexArray(this.wireVao)
    if (this.solidProgram) gl.deleteProgram(this.solidProgram.program)
    if (this.wireProgram) gl.deleteProgram(this.wireProgram.program)
  }
}
