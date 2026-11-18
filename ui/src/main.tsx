import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Route, Routes } from 'react-router-dom';
import './index.css';
import Layout from './components/Layout';
import Overview from './pages/Overview';
import Traces from './pages/Traces';
import TraceDetail from './pages/TraceDetail';
import ServiceDetail from './pages/ServiceDetail';
import ServiceMap from './pages/ServiceMap';
import Alerts from './pages/Alerts';
import Sampling from './pages/Sampling';
import Chaos from './pages/Chaos';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<Overview />} />
          <Route path="traces" element={<Traces />} />
          <Route path="traces/:traceId" element={<TraceDetail />} />
          <Route path="services/:service" element={<ServiceDetail />} />
          <Route path="map" element={<ServiceMap />} />
          <Route path="alerts" element={<Alerts />} />
          <Route path="sampling" element={<Sampling />} />
          <Route path="chaos" element={<Chaos />} />
        </Route>
      </Routes>
    </BrowserRouter>
  </React.StrictMode>,
);
