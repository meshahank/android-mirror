import React, { useEffect, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { invoke } from '@tauri-apps/api/core'
import { videoPoint } from './lib/coordinates'
import './styles.css'

type Device = { name: string; host: string; port: number; id: string; lastSeen?: string }
type Signal = { sdpType?: string; sdp?: string; candidate?: string; sdpMid?: string; sdpMLineIndex?: number }
const id = () => crypto.randomUUID()
const envelope = (type: string, body: unknown) => ({ version: 1, type, requestId: id(), timestampMs: Date.now(), body })

async function clientId() {
  let key = localStorage.getItem('android-mirror-client-key')
  if (!key) {
    const pair = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify'])
    key = btoa(String.fromCharCode(...new Uint8Array(await crypto.subtle.exportKey('spki', pair.publicKey))))
    localStorage.setItem('android-mirror-client-key', key)
  }
  return key
}

function App() {
  const [devices, setDevices] = useState<Device[]>([])
  const [viewer, setViewer] = useState<Device | null>(null)
  const [manual, setManual] = useState('')
  const [status, setStatus] = useState('Looking for Android Mirror devices…')
  const video = useRef<HTMLVideoElement>(null)
  const pc = useRef<RTCPeerConnection>()
  const ws = useRef<WebSocket>()

  useEffect(() => {
    invoke<Device[]>('discover_devices').then(found => {
      setDevices(found)
      setStatus(found.length ? 'Select a device to connect.' : 'No devices found. Enter an IP address below.')
    }).catch(() => setStatus('Discovery unavailable; use manual IP.'))
  }, [])

  async function connect(device: Device) {
    setViewer(device)
    setStatus('Connecting to phone…')
    const key = await clientId()
    const code = prompt(`Enter the 6-digit code shown on ${device.name}`) || ''
    if (!code) { setStatus('Pairing cancelled.'); return }

    let phase = 'connecting'
    const socket = new WebSocket(`ws://${device.host}:${device.port}/signal`)
    ws.current = socket
    socket.onopen = () => {
      phase = 'pairing'
      setStatus('Connected to phone. Verifying pair code…')
      socket.send(JSON.stringify(envelope('pair-request', { shortCode: code, computerName: navigator.userAgent.slice(0, 50), clientPublicKey: key })))
    }
    socket.onmessage = async event => {
      try {
        const message = JSON.parse(event.data)
        if (message.type === 'pair-result') {
          if (!message.body.accepted) { phase = 'failed'; setStatus(message.body.reason || 'Pairing rejected by phone.'); return }
          phase = 'session'
          setStatus('Pairing approved. Starting screen stream…')
          socket.send(JSON.stringify(envelope('session-request', { mode: 'view', clientId: key })))
          return
        }
        if (message.type === 'session-status') {
          if (message.body.state === 'rejected' || message.body.state === 'error') {
            phase = 'failed'
            setStatus(message.body.message || 'Phone rejected the viewing session.')
            return
          }
          if (message.body.state !== 'capture-starting') return
          phase = 'negotiating'
          setStatus(message.body.message || 'Phone approved the session. Negotiating video…')
          const peer = new RTCPeerConnection()
          pc.current = peer
          peer.ontrack = track => {
            if (video.current) video.current.srcObject = track.streams[0]
            phase = 'live'
            setStatus('Live • encrypted local connection')
          }
          peer.onconnectionstatechange = () => {
            if (peer.connectionState === 'failed') setStatus('Video negotiation failed. The phone stayed paired; try Connect once more.')
          }
          peer.onicecandidate = ice => {
            if (ice.candidate && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(envelope('signal', { candidate: ice.candidate.candidate, sdpMid: ice.candidate.sdpMid, sdpMLineIndex: ice.candidate.sdpMLineIndex })))
          }
          const offer = await peer.createOffer({ offerToReceiveVideo: true })
          await peer.setLocalDescription(offer)
          socket.send(JSON.stringify(envelope('signal', { sdpType: 'offer', sdp: offer.sdp })))
          return
        }
        if (message.type === 'signal') {
          const signal: Signal = message.body
          if (signal.sdp) await pc.current?.setRemoteDescription({ type: signal.sdpType as RTCSdpType, sdp: signal.sdp })
          else if (signal.candidate) await pc.current?.addIceCandidate({ candidate: signal.candidate, sdpMid: signal.sdpMid, sdpMLineIndex: signal.sdpMLineIndex })
        }
      } catch (error) {
        phase = 'failed'
        setStatus(`Desktop protocol error: ${error instanceof Error ? error.message : 'invalid response from phone'}`)
      }
    }
    socket.onerror = () => { if (phase !== 'live') setStatus('WebSocket connection failed. Check Wi-Fi and the phone sharing notification.') }
    socket.onclose = event => {
      if (phase === 'live') setStatus('Mirroring session ended.')
      else if (phase !== 'failed') setStatus(`Phone closed the connection (code ${event.code}${event.reason ? ': ' + event.reason : ''}).`)
    }
  }

  function control(kind: string, extra: object = {}) { if (ws.current?.readyState === WebSocket.OPEN) ws.current.send(JSON.stringify(envelope('control', { kind, ...extra }))) }
  function point(event: React.PointerEvent, kind: string) { const element = event.currentTarget as HTMLVideoElement; const point = videoPoint(event.clientX, event.clientY, element.getBoundingClientRect(), element.videoWidth, element.videoHeight); if (point) control(kind, point) }
  function close() { pc.current?.close(); ws.current?.close(); setViewer(null); setStatus('Disconnected') }

  if (viewer) return <main className="viewer"><header><button onClick={close}>‹ Devices</button><span>{viewer.name}</span><small>{status}</small><button onClick={() => control('global', { action: 'back' })}>Back</button><button onClick={() => control('global', { action: 'home' })}>Home</button><button onClick={() => control('global', { action: 'recents' })}>Recents</button></header><section className="stage"><video ref={video} autoPlay playsInline onPointerDown={event => point(event, 'tap')} onWheel={event => { event.preventDefault(); control('scroll', { x: .5, y: .5, endX: .5, endY: event.deltaY > 0 ? .25 : .75, durationMs: 170 }) }} /></section></main>
  return <main className="home"><h1>Android Mirror</h1><p>Your devices</p><div className="devices">{devices.map(device => <button className="device" key={device.id} onClick={() => connect(device)}><b>{device.name}</b><span>Android · {device.host}</span><em>Available</em></button>)}{!devices.length && <div className="empty">{status}</div>}</div><form onSubmit={event => { event.preventDefault(); const [host, port = '38475'] = manual.split(':'); connect({ name: host, host, port: +port, id: host }) }}><label>Pair by IP address</label><input value={manual} onChange={event => setManual(event.target.value)} placeholder="192.168.1.20 or 192.168.1.20:38475" /><button>Connect</button></form><small>{status}</small></main>
}

createRoot(document.getElementById('root')!).render(<App />)
