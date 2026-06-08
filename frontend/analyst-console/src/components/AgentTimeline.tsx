import type { ReactNode } from 'react';
import { Timeline, Tag, Typography, Collapse } from 'antd';
import {
  SearchOutlined,
  ApiOutlined,
  CheckCircleOutlined,
  ProfileOutlined,
  NodeIndexOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import type { AgentStep } from '../types/investigation';

const { Text, Paragraph } = Typography;

const TOOL_ICON: Record<string, ReactNode> = {
  sanctions_screen: <SafetyCertificateOutlined />,
  address_profile: <ProfileOutlined />,
  trace_transactions: <NodeIndexOutlined />,
  risk_rules: <ApiOutlined />,
};

function iconFor(step: AgentStep): ReactNode {
  if (step.phase === 'FINISH') return <CheckCircleOutlined />;
  if (step.toolName && TOOL_ICON[step.toolName]) return TOOL_ICON[step.toolName];
  return <SearchOutlined />;
}

export function AgentTimeline({ steps }: { steps: AgentStep[] }) {
  if (!steps.length) {
    return <Text type="secondary">Waiting for the agent to take its first step…</Text>;
  }

  return (
    <Timeline
      mode="left"
      items={steps.map((step) => ({
        dot: iconFor(step),
        color: step.phase === 'FINISH' ? 'green' : 'blue',
        children: (
          <div>
            <div style={{ marginBottom: 4 }}>
              <Tag color={step.phase === 'FINISH' ? 'green' : 'geekblue'}>
                #{step.index} {step.phase}
              </Tag>
              {step.toolName && <Tag color="purple">{step.toolName}</Tag>}
              {step.durationMs != null && (
                <Text type="secondary" style={{ fontSize: 12 }}>
                  {step.durationMs} ms
                </Text>
              )}
            </div>
            <Paragraph style={{ marginBottom: 6 }}>{step.thought}</Paragraph>
            {(step.toolArgs != null || step.observation != null) && (
              <Collapse
                size="small"
                ghost
                items={[
                  {
                    key: 'io',
                    label: 'tool call + observation',
                    children: (
                      <pre style={{ fontSize: 12, whiteSpace: 'pre-wrap', margin: 0 }}>
                        {JSON.stringify(
                          { args: step.toolArgs, observation: step.observation },
                          null,
                          2,
                        )}
                      </pre>
                    ),
                  },
                ]}
              />
            )}
          </div>
        ),
      }))}
    />
  );
}
