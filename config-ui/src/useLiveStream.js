import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * Live connection to the config server's WebSocket.
 *
 * Browsers cannot set headers on a WebSocket handshake, so the key travels as a query
 * parameter (the server's filter accepts it). Reconnects use jittered exponential
 * backoff, mirroring the embedded SDK.
 */
export function useLiveStream({ apiKey, enabled = true, onFrame }) {
  const [status, setStatus] = useState('idle')
  const [version, setVersion] = useState(null)
  const socketRef = useRef(null)
  const attemptRef = useRef(0)
  const timerRef = useRef(null)
  const frameHandler = useRef(onFrame)

  frameHandler.current = onFrame

  const connect = useCallback(() => {
    if (!enabled || !apiKey) return undefined

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const url = `${protocol}//${window.location.host}/ws/config?apiKey=${encodeURIComponent(apiKey)}`

    setStatus('connecting')
    const socket = new WebSocket(url)
    socketRef.current = socket

    socket.onopen = () => {
      attemptRef.current = 0
      setStatus('connected')
    }

    socket.onmessage = (event) => {
      let frame
      try {
        frame = JSON.parse(event.data)
      } catch {
        return
      }
      if (typeof frame.version === 'number') {
        setVersion(frame.version)
      }
      if (frame.type !== 'hello') {
        frameHandler.current?.(frame)
      }
    }

    socket.onclose = () => {
      setStatus('disconnected')
      if (!enabled) return
      // Jittered backoff, capped, so a restart does not stampede the server.
      const attempt = attemptRef.current++
      const wait = Math.min(15000, 500 * 2 ** attempt) + Math.random() * 500
      timerRef.current = window.setTimeout(connect, wait)
    }

    socket.onerror = () => {
      setStatus('error')
    }

    return undefined
  }, [apiKey, enabled])

  useEffect(() => {
    connect()
    return () => {
      window.clearTimeout(timerRef.current)
      const socket = socketRef.current
      socketRef.current = null
      if (socket && socket.readyState <= WebSocket.OPEN) {
        socket.close()
      }
    }
  }, [connect])

  const subscribe = useCallback((currentVersion) => {
    const socket = socketRef.current
    if (socket && socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify({ type: 'subscribe', version: currentVersion ?? 0 }))
    }
  }, [])

  return { status, version, subscribe }
}
