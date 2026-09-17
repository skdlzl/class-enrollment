import { Navigate, Route, Routes } from "react-router-dom";
import AdminPage from "./pages/AdminPage";
import EnrollmentPage from "./pages/EnrollmentPage";

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<EnrollmentPage />} />
      <Route path="/admin" element={<AdminPage />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
