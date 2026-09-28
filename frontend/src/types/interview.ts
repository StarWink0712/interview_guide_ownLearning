// 面试相关类型定义

import type { CategoryDTO } from '../api/skill';

export interface InterviewSession {
  sessionId: string;
  resumeText: string;
  totalQuestions: number;
  currentQuestionIndex: number;
  questions: InterviewQuestion[];
  status: 'CREATED' | 'IN_PROGRESS' | 'COMPLETED' | 'EVALUATED';
}

export interface InterviewQuestion {
  questionIndex: number;
  question: string;
  type: string;
  category: string;
  userAnswer: string | null;
  score: number | null;
  feedback: string | null;
}

export interface CreateInterviewRequest {
  resumeText: string;
  questionCount: number;
  resumeId?: number;
  forceCreate?: boolean;
  llmProvider?: string;
  skillId: string;
  difficulty?: string;
  customCategories?: CategoryDTO[];
  jdText?: string;
}

export interface SubmitAnswerRequest {
  sessionId: string;
  questionIndex: number;
  answer: string;
}

export interface SubmitAnswerResponse {
  hasNextQuestion: boolean;
  nextQuestion: InterviewQuestion | null;
  currentIndex: number;
  totalQuestions: number;
  turnEvaluation?: TurnEvaluation;
  adaptiveDecision?: AdaptiveDecision;
  abilityProfile?: AbilityProfile;
}

export interface CurrentQuestionResponse {
  completed: boolean;
  question?: InterviewQuestion;
  message?: string;
}

export interface TurnEvaluation {
  questionIndex: number;
  score: number;
  depthLevel: number;
  missingKeyPoints: string[];
  followUpSuggestion: string;
  category: string;
}

export interface AdaptiveDecision {
  action: 'FOLLOW_UP' | 'NEXT_CATEGORY' | 'RAISE_DIFFICULTY' | 'LOWER_DIFFICULTY'
    | 'DEEP_DIVE_PROJECT' | 'END_PHASE';
  targetCategory: string;
  nextDifficulty: string;
  reason: string;
  nextQuestion: string | null;
}

export interface AbilityDimension {
  category: string;
  score: number;
  confidence: number;
  questionCount: number;
  recentPerformance: string;
  weakKeywords: string[];
}

export interface AbilityProfile {
  dimensions: AbilityDimension[];
  averageScore: number;
  summary: string;
}

export interface AdaptiveTrace {
  questionIndex: number;
  category: string;
  turnEvaluation: TurnEvaluation;
  adaptiveDecision: AdaptiveDecision;
}

export interface InterviewReport {
  sessionId: string;
  totalQuestions: number;
  overallScore: number;
  categoryScores: CategoryScore[];
  questionDetails: QuestionEvaluation[];
  overallFeedback: string;
  strengths: string[];
  improvements: string[];
  referenceAnswers: ReferenceAnswer[];
  abilityProfile?: AbilityProfile;
  adaptiveTrace?: AdaptiveTrace[];
}

export interface CategoryScore {
  category: string;
  score: number;
  questionCount: number;
}

export interface QuestionEvaluation {
  questionIndex: number;
  question: string;
  category: string;
  userAnswer: string;
  score: number;
  feedback: string;
}

export interface ReferenceAnswer {
  questionIndex: number;
  question: string;
  referenceAnswer: string;
  keyPoints: string[];
}
