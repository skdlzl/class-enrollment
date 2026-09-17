export type Student = {
  id: number;
  name: string;
};

export type Course = {
  id: number;
  code: string;
  name: string;
  professor: string;
  department: string;
  day: string;
  time: string;
  room: string;
  credits: number;
  capacity: number;
  enrolled: number;
};

export type EnrollmentMap = Record<number, number[]>;
