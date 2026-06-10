const EPSILON = 1e-10

class Vertex {
  constructor(x, y, z, intensity, index, isInterpolated) {
    this.x = x
    this.y = y
    this.z = z
    this.intensity = intensity
    this.index = index
    this.isInterpolated = isInterpolated || false
  }
}

class Edge {
  constructor(v0, v1) {
    this.v0 = v0
    this.v1 = v1
  }

  equals(other) {
    return (this.v0 === other.v0 && this.v1 === other.v1) ||
           (this.v0 === other.v1 && this.v1 === other.v0)
  }

  key() {
    const a = Math.min(this.v0, this.v1)
    const b = Math.max(this.v0, this.v1)
    return a + '|' + b
  }
}

class Triangle {
  constructor(v0, v1, v2) {
    this.v0 = v0
    this.v1 = v1
    this.v2 = v2
    this.calcCircumcircle()
  }

  calcCircumcircle() {
    const ax = this.v0.x, ay = this.v0.y
    const bx = this.v1.x, by = this.v1.y
    const cx = this.v2.x, cy = this.v2.y

    const d = 2.0 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
    if (Math.abs(d) < EPSILON) {
      this.cx = 0
      this.cy = 0
      this.cr = Infinity
      return
    }

    const a2 = ax * ax + ay * ay
    const b2 = bx * bx + by * by
    const c2 = cx * cx + cy * cy

    this.cx = (a2 * (by - cy) + b2 * (cy - ay) + c2 * (ay - by)) / d
    this.cy = (a2 * (cx - bx) + b2 * (ax - cx) + c2 * (bx - ax)) / d

    const dx = ax - this.cx
    const dy = ay - this.cy
    this.cr = Math.sqrt(dx * dx + dy * dy)
  }

  inCircumcircle(v) {
    const dx = v.x - this.cx
    const dy = v.y - this.cy
    return Math.sqrt(dx * dx + dy * dy) < this.cr + EPSILON
  }

  hasVertex(vIdx) {
    return this.v0.index === vIdx || this.v1.index === vIdx || this.v2.index === vIdx
  }

  edges() {
    return [
      new Edge(this.v0.index, this.v1.index),
      new Edge(this.v1.index, this.v2.index),
      new Edge(this.v2.index, this.v0.index)
    ]
  }
}

export class DelaunayTriangulation {
  constructor() {
    this.vertices = []
    this.triangles = []
  }

  triangulate(points) {
    if (!points || points.length < 3) {
      this.vertices = []
      this.triangles = []
      return { vertices: [], indices: [] }
    }

    this.vertices = []
    this.triangles = []

    const n = points.length
    for (let i = 0; i < n; i++) {
      this.vertices.push(new Vertex(points[i].x, points[i].y, points[i].z, points[i].intensity, i, points[i].isInterpolated))
    }

    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
    for (let i = 0; i < n; i++) {
      const v = this.vertices[i]
      if (v.x < minX) minX = v.x
      if (v.y < minY) minY = v.y
      if (v.x > maxX) maxX = v.x
      if (v.y > maxY) maxY = v.y
    }

    const dx = maxX - minX
    const dy = maxY - minY
    const dmax = Math.max(dx, dy, 1)
    const midX = (minX + maxX) / 2
    const midY = (minY + maxY) / 2

    const superV0 = new Vertex(midX - 20 * dmax, midY - dmax, 0, 0, n)
    const superV1 = new Vertex(midX, midY + 20 * dmax, 0, 0, n + 1)
    const superV2 = new Vertex(midX + 20 * dmax, midY - dmax, 0, 0, n + 2)

    this.vertices.push(superV0, superV1, superV2)
    this.triangles.push(new Triangle(superV0, superV1, superV2))

    for (let i = 0; i < n; i++) {
      this._addVertex(this.vertices[i])
    }

    this.triangles = this.triangles.filter(t =>
      t.v0.index < n && t.v1.index < n && t.v2.index < n
    )

    return this._buildMesh()
  }

  _addVertex(v) {
    const badTriangles = []

    for (let i = 0; i < this.triangles.length; i++) {
      if (this.triangles[i].inCircumcircle(v)) {
        badTriangles.push(i)
      }
    }

    const boundaryEdges = []
    for (let i = 0; i < badTriangles.length; i++) {
      const edges = this.triangles[badTriangles[i]].edges()
      for (let j = 0; j < 3; j++) {
        let shared = false
        for (let k = 0; k < badTriangles.length; k++) {
          if (k === i) continue
          const otherEdges = this.triangles[badTriangles[k]].edges()
          for (let m = 0; m < 3; m++) {
            if (edges[j].equals(otherEdges[m])) {
              shared = true
              break
            }
          }
          if (shared) break
        }
        if (!shared) {
          boundaryEdges.push(edges[j])
        }
      }
    }

    const removeSet = new Set(badTriangles)
    const newTriangles = []
    for (let i = 0; i < this.triangles.length; i++) {
      if (!removeSet.has(i)) {
        newTriangles.push(this.triangles[i])
      }
    }
    this.triangles = newTriangles

    for (let i = 0; i < boundaryEdges.length; i++) {
      const e = boundaryEdges[i]
      const tri = new Triangle(this.vertices[e.v0], this.vertices[e.v1], v)
      this.triangles.push(tri)
    }
  }

  _buildMesh() {
    const verts = []
    const indices = []
    const n = this.vertices.length - 3

    for (let i = 0; i < n; i++) {
      const v = this.vertices[i]
      verts.push(v.x, v.y, v.z, v.intensity, v.isInterpolated ? 1.0 : 0.0)
    }

    for (let i = 0; i < this.triangles.length; i++) {
      const t = this.triangles[i]
      indices.push(t.v0.index, t.v1.index, t.v2.index)
    }

    return { vertices: new Float32Array(verts), indices: new Uint32Array(indices) }
  }

  getNeighbors(vertexIndex) {
    const neighbors = new Set()
    for (let i = 0; i < this.triangles.length; i++) {
      const t = this.triangles[i]
      if (t.v0.index === vertexIndex) {
        neighbors.add(t.v1.index)
        neighbors.add(t.v2.index)
      } else if (t.v1.index === vertexIndex) {
        neighbors.add(t.v0.index)
        neighbors.add(t.v2.index)
      } else if (t.v2.index === vertexIndex) {
        neighbors.add(t.v0.index)
        neighbors.add(t.v1.index)
      }
    }
    return neighbors
  }
}
