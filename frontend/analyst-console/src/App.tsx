import { ConfigProvider } from 'antd';
import { InvestigatePage } from './pages/InvestigatePage';

export default function App() {
  return (
    <ConfigProvider theme={{ token: { colorPrimary: '#1f6feb' } }}>
      <InvestigatePage />
    </ConfigProvider>
  );
}
