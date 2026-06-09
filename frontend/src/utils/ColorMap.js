export function depthToColor(depth) {
  if (depth < 0) depth = 0

  if (depth <= 50) {
    const t = depth / 50
    return {
      r: 0.0 + t * 0.0,
      g: 0.9 - t * 0.3,
      b: 0.95 - t * 0.15,
      a: 1.0
    }
  } else if (depth <= 200) {
    const t = (depth - 50) / 150
    return {
      r: 0.0,
      g: 0.6 - t * 0.4,
      b: 0.8 - t * 0.3,
      a: 1.0
    }
  } else if (depth <= 500) {
    const t = (depth - 200) / 300
    return {
      r: 0.0 + t * 0.05,
      g: 0.2 - t * 0.15,
      b: 0.5 - t * 0.25,
      a: 1.0
    }
  } else {
    const t = Math.min((depth - 500) / 500, 1.0)
    return {
      r: 0.05 + t * 0.02,
      g: 0.05 - t * 0.03,
      b: 0.25 - t * 0.15,
      a: 1.0
    }
  }
}

export function depthToCSSColor(depth) {
  const c = depthToColor(depth)
  const r = Math.round(c.r * 255)
  const g = Math.round(c.g * 255)
  const b = Math.round(c.b * 255)
  return `rgb(${r}, ${g}, ${b})`
}
