import { Request, Response, NextFunction } from 'express';
import { logger } from '../utils/logger';

export class AppError extends Error {
  constructor(
    public statusCode: number,
    public message: string,
    public isOperational = true
  ) {
    super(message);
    Object.setPrototypeOf(this, AppError.prototype);
  }
}

export function errorHandler(
  error: Error | AppError,
  req: Request,
  res: Response,
  next: NextFunction
) {
  if (error instanceof AppError) {
    logger.warn(`AppError: ${error.message}`, {
      statusCode: error.statusCode,
      path: req.path
    });

    return res.status(error.statusCode).json({
      error: error.message,
      statusCode: error.statusCode
    });
  }

  // Unexpected errors
  logger.error('Unexpected error:', {
    error: error.message,
    stack: error.stack,
    path: req.path
  });

  res.status(500).json({
    error: 'Internal Server Error',
    statusCode: 500
  });
}
